/**
 * Copyright 2012-2020 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.Map.Entry;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.Param.Expander;
import feign.Request.Options;
import feign.codec.*;
import feign.template.UriUtils;

public class ReflectiveFeign extends Feign {

  // 文上有解释次类的作用：提供方法，给接口每个方法生成一个处理器
  private final ParseHandlersByName targetToHandlersByName;
  // 它是调度中心
  private final InvocationHandlerFactory factory;
  private final QueryMapEncoder queryMapEncoder;

  ReflectiveFeign(ParseHandlersByName targetToHandlersByName, InvocationHandlerFactory factory,
      QueryMapEncoder queryMapEncoder) {
    this.targetToHandlersByName = targetToHandlersByName;
    this.factory = factory;
    this.queryMapEncoder = queryMapEncoder;
  }

  /**
   * creates an api binding to the {@code target}. As this invokes reflection, care should be taken
   * to cache the result.
   */
  @SuppressWarnings("unchecked")
  @Override
  public <T> T newInstance(Target<T> target) {
    // 拿到该接口所有方法对应的处理器的Map
    Map<String, MethodHandler> nameToHandler = targetToHandlersByName.apply(target);
    // 真要处理调用的Method对应的处理器Map
    Map<Method, MethodHandler> methodToHandler = new LinkedHashMap<Method, MethodHandler>();
    // 简单的说：对接口默认方法作为处理方法提供支持，不用发http请求喽，一般可忽略
    List<DefaultMethodHandler> defaultMethodHandlers = new LinkedList<DefaultMethodHandler>();
    // 查阅该接口所有的Method。
    // .getMethods()会获取接口的所有的public方法，包括default方法哦（因为defualt方法也是public的）
    for (Method method : target.type().getMethods()) {
      if (method.getDeclaringClass() == Object.class) {
        continue;
      } else if (Util.isDefault(method)) {
        DefaultMethodHandler handler = new DefaultMethodHandler(method);
        defaultMethodHandlers.add(handler);
        methodToHandler.put(method, handler);
      } else {
        methodToHandler.put(method, nameToHandler.get(Feign.configKey(target.type(), method)));
      }
    }
    InvocationHandler handler = factory.create(target, methodToHandler);
    T proxy = (T) Proxy.newProxyInstance(target.type().getClassLoader(),
        new Class<?>[] {target.type()}, handler);

    for (DefaultMethodHandler defaultMethodHandler : defaultMethodHandlers) {
      defaultMethodHandler.bindTo(proxy);
    }
    return proxy;
  }

  static class FeignInvocationHandler implements InvocationHandler {

    private final Target target;
    private final Map<Method, MethodHandler> dispatch;

    FeignInvocationHandler(Target target, Map<Method, MethodHandler> dispatch) {
      this.target = checkNotNull(target, "target");
      this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      //重写几个方法
      if ("equals".equals(method.getName())) {
        try {
          Object otherHandler =
              args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
          return equals(otherHandler);
        } catch (IllegalArgumentException e) {
          return false;
        }
      } else if ("hashCode".equals(method.getName())) {
        return hashCode();
      } else if ("toString".equals(method.getName())) {
        return toString();
      }
        //获取SynchronousMethodHandler执行
      return dispatch.get(method).invoke(args);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof FeignInvocationHandler) {
        FeignInvocationHandler other = (FeignInvocationHandler) obj;
        return target.equals(other.target);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return target.hashCode();
    }

    @Override
    public String toString() {
      return target.toString();
    }
  }

  /**
   * 该类有且仅提供一个方法：将feign.Target转为Map<String, MethodHandler>，
   * 就是说为每一个configKey（其实就是一个Method），找到一个处理它的MethodHandler。
   * 我们传入Target（封装了我们的模拟接口，要访问的域名），返回这个接口下的各个方法，对应的执行HTTP请求需要的一系列信息
   * 结果Map<String, MethodHandler>的key是这个接口中的方法名字，MethodHandler则是包含此方法执行需要的各种信息。
   *
   * 内部几个组件：准备一个http请求执行前的各种数据，定义http执行后对于结果的各种处理逻辑。
   * 所以这些组件的作用是 “处理每个HTTP执行前后的事情”
   *
   * 准备好执行这个HTTP请求所需要的一切，为指定接口类型的每个方法生成其对应的MethodHandler处理器
   *
   * 涉及到元数据提取、编码、模版数据填充等动作，均交给不同的组件去完成，组件化的设计有助于模块化、可插拔等特性。
   */
  static final class ParseHandlersByName {
    //作用是将我们传入的接口进行解析验证，看注解的使用是否符合规范，
    // 然后返回给我们接口上各种相应的元数据。所以叫合约。详见：https://www.jianshu.com/p/6582f8319f72
    private final Contract contract;
    //封装Request请求的 连接超时=默认10s ，读取超时=默认60s
    private final Options options;
    //怎么把我们的请求编码
    private final Encoder encoder;
    //怎么把我们执行HTTP请求后得到的结果解码为我们定义的类型
    private final Decoder decoder;
    //怎么在我们执行HTTP请求后得到的错误(既不是2xx的状态码)解码为我们定义的类型
    private final ErrorDecoder errorDecoder;
    private final QueryMapEncoder queryMapEncoder;
    // 产生SynchronousMethodHandler实例的工厂，SynchronousMethodHandler实例执行HTTP请求
    // 从这也很好理解：所有的Method最终的处理器都是SynchronousMethodHandler
    private final SynchronousMethodHandler.Factory factory;

    ParseHandlersByName(
        Contract contract,
        Options options,
        Encoder encoder,
        Decoder decoder,
        QueryMapEncoder queryMapEncoder,
        ErrorDecoder errorDecoder,
        SynchronousMethodHandler.Factory factory) {
      this.contract = contract;
      this.options = options;
      this.factory = factory;
      this.errorDecoder = errorDecoder;
      this.queryMapEncoder = queryMapEncoder;
      this.encoder = checkNotNull(encoder, "encoder");
      this.decoder = checkNotNull(decoder, "decoder");
    }

    //ParseHandlersByName最后把以上的组件都封装为了一个结果集Map<String, MethodHandler>
    public Map<String, MethodHandler> apply(Target target) {
      // 通过Contract提取出该类所有方法的元数据信息：MethodMetadata
      // 它会解析注解，不同的实现支持的注解是不一样的
      List<MethodMetadata> metadata = contract.parseAndValidateMetadata(target.type());
      // 一个方法一个方法的处理，生成器对应的MethodHandler处理器
      // 上篇文章有讲过，元数据都是交给RequestTemplate.Factory去构建成为一个请求模版的
      Map<String, MethodHandler> result = new LinkedHashMap<String, MethodHandler>();
      for (MethodMetadata md : metadata) {
        // 这里其实我觉得使用接口RequestTemplate.Factory buildTemplate更加的合适
        BuildTemplateByResolvingArgs buildTemplate;
        // 针对不同元数据参数，调用不同的RequestTemplate.Factory实现类完成处理
        if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {
          // 若存在表单参数formParams，并且没有body模版，说明请求的参数就是简单的Query参数。那就执行表单形式的构建
          buildTemplate =
              new BuildFormEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
        } else if (md.bodyIndex() != null) {
          // 若存在body，那就是body喽
          buildTemplate = new BuildEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
        } else {
          // 否则就是普通形式：查询参数构建方式
          buildTemplate = new BuildTemplateByResolvingArgs(md, queryMapEncoder, target);
        }
        if (md.isIgnored()) {
          // 通过factory.create创建出MethodHandler实例，缓存结果
          result.put(md.configKey(), args -> {
            throw new IllegalStateException(md.configKey() + " is not a method handled by feign");
          });
        } else {
          result.put(md.configKey(),
              factory.create(target, md, buildTemplate, options, decoder, errorDecoder));
        }
      }
      return result;
      //ParseHandlersByName类其实就类似于一个工具类，里面调用了contract接口的解析获得了MethodMetadata列表
      //，然后在将方法包装成MethodHandler，最后根据configKey返回一个Map
    }
  }

  /**
   * 除表单、请求体body之外的处理实例
   */
  private static class BuildTemplateByResolvingArgs implements RequestTemplate.Factory {

    private final QueryMapEncoder queryMapEncoder;
    //方法元数据
    protected final MethodMetadata metadata;
    //目标对象
    protected final Target<?> target;
    //@Param参数中的Expander，key是哪个参数的序号
    private final Map<Integer, Expander> indexToExpander = new LinkedHashMap<Integer, Expander>();

    private BuildTemplateByResolvingArgs(MethodMetadata metadata, QueryMapEncoder queryMapEncoder,
        Target target) {
      this.metadata = metadata;
      this.target = target;
      this.queryMapEncoder = queryMapEncoder;
      //找到MD中是否有expander信息，有就倒腾过来。
      //注意这里其实一开始是没有的。因为在Contract的解析逻辑中我们是把Expander的信息放在了indexToExpanderClass里面，没有去实例化那些Expander类。
      //只有重写了Contract逻辑，调用了feign.MethodMetadata#indexToExpander(java.util.Map<java.lang.Integer,feign.Param.Expander>)方法，动态注入了Expander实例的情况才会使用。
      //这样原本的indexToExpanderClass就没用了，因为可以看到代码直接返回了。
      if (metadata.indexToExpander() != null) {
        indexToExpander.putAll(metadata.indexToExpander());
        return;
      }
      if (metadata.indexToExpanderClass().isEmpty()) {
        return;
      }
      //如果MD中填充了indexToExpanderClass相关信息，将Expander实例化，放入工厂类自己的indexToExpander待用。
      for (Entry<Integer, Class<? extends Expander>> indexToExpanderClass : metadata
          .indexToExpanderClass().entrySet()) {
        try {
          indexToExpander
              .put(indexToExpanderClass.getKey(), indexToExpanderClass.getValue().newInstance());
        } catch (InstantiationException e) {
          throw new IllegalStateException(e);
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }

    @Override
    public RequestTemplate create(Object[] argv) {
      //将md中原本的RequestTemplate取出，创建新的RequestTemplate，以供修改，咋改呢？
      RequestTemplate mutable = RequestTemplate.from(metadata.template());
      // 设置目标对象
      mutable.feignTarget(target);
      //处理URI类型参数
      if (metadata.urlIndex() != null) {
        // 有URL 则设置请求的目标（地址）
        int urlIndex = metadata.urlIndex();
        checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
        mutable.target(String.valueOf(argv[urlIndex]));
      }
      //获取@Param参数，替换模板，处理参数
      Map<String, Object> varBuilder = new LinkedHashMap<String, Object>();
      for (Entry<Integer, Collection<String>> entry : metadata.indexToName().entrySet()) {
        int i = entry.getKey();
        Object value = argv[entry.getKey()];
        if (value != null) { // Null values are skipped.
          if (indexToExpander.containsKey(i)) {
            value = expandElements(indexToExpander.get(i), value);
          }
          // 循环，将参数名，参数类型解析为Map ,全部解析完后，还是会回到 this.resolve(argv, mutable, varBuilder)
          for (String name : entry.getValue()) {
            varBuilder.put(name, value);
          }
        }
      }
      // 调用RequestTemplate 的解析方法
      RequestTemplate template = resolve(argv, mutable, varBuilder);
      //处理@QueryMap参数
      if (metadata.queryMapIndex() != null) {
        // add query map parameters after initial resolve so that they take
        // precedence over any predefined values
        // 获取@SpringQueryMap标识的参数对象
        Object value = argv[metadata.queryMapIndex()];
        // 转对象为MAP
        Map<String, Object> queryMap = toQueryMap(value);
        // 添加请求参数
        template = addQueryMapQueryParameters(queryMap, template);
      }
      // 处理@HeaderMap参数
      if (metadata.headerMapIndex() != null) {
        template =
            addHeaderMapHeaders((Map<String, Object>) argv[metadata.headerMapIndex()], template);
      }

      return template;
    }

    private Map<String, Object> toQueryMap(Object value) {
      if (value instanceof Map) {
        return (Map<String, Object>) value;
      }
      try {
        return queryMapEncoder.encode(value);
      } catch (EncodeException e) {
        throw new IllegalStateException(e);
      }
    }

    private Object expandElements(Expander expander, Object value) {
      if (value instanceof Iterable) {
        return expandIterable(expander, (Iterable) value);
      }
      return expander.expand(value);
    }

    private List<String> expandIterable(Expander expander, Iterable value) {
      List<String> values = new ArrayList<String>();
      for (Object element : value) {
        if (element != null) {
          values.add(expander.expand(element));
        }
      }
      return values;
    }

    //给RequestTemplate添加消息头
    @SuppressWarnings("unchecked")
    private RequestTemplate addHeaderMapHeaders(Map<String, Object> headerMap,
                                                RequestTemplate mutable) {
      for (Entry<String, Object> currEntry : headerMap.entrySet()) {
        Collection<String> values = new ArrayList<String>();

        Object currValue = currEntry.getValue();
        if (currValue instanceof Iterable<?>) {
          Iterator<?> iter = ((Iterable<?>) currValue).iterator();
          while (iter.hasNext()) {
            Object nextObject = iter.next();
            values.add(nextObject == null ? null : nextObject.toString());
          }
        } else {
          values.add(currValue == null ? null : currValue.toString());
        }

        mutable.header(currEntry.getKey(), values);
      }
      return mutable;
    }

    //给RequestTemplate添加请求参数
    @SuppressWarnings("unchecked")
    private RequestTemplate addQueryMapQueryParameters(Map<String, Object> queryMap,
                                                       RequestTemplate mutable) {
      for (Entry<String, Object> currEntry : queryMap.entrySet()) {
        Collection<String> values = new ArrayList<String>();

        boolean encoded = metadata.queryMapEncoded();
        Object currValue = currEntry.getValue();
        if (currValue instanceof Iterable<?>) {
          Iterator<?> iter = ((Iterable<?>) currValue).iterator();
          while (iter.hasNext()) {
            Object nextObject = iter.next();
            values.add(nextObject == null ? null
                : encoded ? nextObject.toString()
                    : UriUtils.encode(nextObject.toString()));
          }
        } else if (currValue instanceof Object[]) {
          for (Object value : (Object[]) currValue) {
            values.add(value == null ? null
                : encoded ? value.toString() : UriUtils.encode(value.toString()));
          }
        } else {
          values.add(currValue == null ? null
              : encoded ? currValue.toString() : UriUtils.encode(currValue.toString()));
        }

        mutable.query(encoded ? currEntry.getKey() : UriUtils.encode(currEntry.getKey()), values);
      }
      return mutable;
    }

    protected RequestTemplate resolve(Object[] argv,
                                      RequestTemplate mutable,
                                      Map<String, Object> variables) {
      return mutable.resolve(variables);
    }
  }

  /**
   * 表单处理实例
   */
  private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

    //请求参数编码
    private final Encoder encoder;

    private BuildFormEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
        QueryMapEncoder queryMapEncoder, Target target) {
      super(metadata, queryMapEncoder, target);
      this.encoder = encoder;
    }

    @Override
    protected RequestTemplate resolve(Object[] argv,
                                      RequestTemplate mutable,
                                      Map<String, Object> variables) {
      Map<String, Object> formVariables = new LinkedHashMap<String, Object>();
      for (Entry<String, Object> entry : variables.entrySet()) {
        if (metadata.formParams().contains(entry.getKey())) {
          formVariables.put(entry.getKey(), entry.getValue());
        }
      }
      try {
        encoder.encode(formVariables, Encoder.MAP_STRING_WILDCARD, mutable);
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      return super.resolve(argv, mutable, variables);
    }
  }

  /**
   * 包含请求体body处理实例
   */
  private static class BuildEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

    private final Encoder encoder;

    private BuildEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
        QueryMapEncoder queryMapEncoder, Target target) {
      super(metadata, queryMapEncoder, target);
      this.encoder = encoder;
    }

    @Override
    protected RequestTemplate resolve(Object[] argv,
                                      RequestTemplate mutable,
                                      Map<String, Object> variables) {
      // 请求参数=》 order 对象
      Object body = argv[metadata.bodyIndex()];
      checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
      try {
        // 编码器编码
        encoder.encode(body, metadata.bodyType(), mutable);
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      return super.resolve(argv, mutable, variables);
    }
  }
}
