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

import static feign.Util.checkState;
import static feign.Util.emptyToNull;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import feign.Request.HttpMethod;

/**
 * Defines what annotations and values are valid on interfaces.
 */

/**
 * 意为契约，实际就是Feign 中的接口解析器，会解析接口方法上的注解为元数据，以此来创建请求模板及方法执行器。
 */
public interface Contract {

  /**
   * Called to parse the methods in the class that are linked to HTTP requests.
   *
   * @param targetType {@link feign.Target#type() type} of the Feign interface.
   */
  /**
   * 解析类中的方法，每个方法解析为MethodMetadata
   * @param targetType  开发者编写的接口的元信息
   * @return  每个方法解析为一个MethodMetadata对象
   */
  List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType);

  abstract class BaseContract implements Contract {

    /**
     * @param targetType {@link feign.Target#type() type} of the Feign interface.
     * @see #parseAndValidateMetadata(Class)
     */
    // 类型class信息传入，该为开发者编写的接口class信息。
    @Override
    public List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType) {
      //接口上不能有泛型变量
      checkState(targetType.getTypeParameters().length == 0, "Parameterized types unsupported: %s",
          targetType.getSimpleName());
      //接口最多有一个父接口
      checkState(targetType.getInterfaces().length <= 1, "Only single inheritance supported: %s",
          targetType.getSimpleName());
      if (targetType.getInterfaces().length == 1) {
        //父接口存在时，父接口的父接口不能存在
        checkState(targetType.getInterfaces()[0].getInterfaces().length == 0,
            "Only single-level inheritance supported: %s",
            targetType.getSimpleName());
      }
      final Map<String, MethodMetadata> result = new LinkedHashMap<String, MethodMetadata>();
      //遍历接口方法
      for (final Method method : targetType.getMethods()) {
        //过滤掉Object的方法、static方法、default方法
        if (method.getDeclaringClass() == Object.class ||
            (method.getModifiers() & Modifier.STATIC) != 0 ||
            Util.isDefault(method)) {
          continue;
        }
        //解析出每个方法的元数据 MethodMetadata
        final MethodMetadata metadata = parseAndValidateMetadata(targetType, method);
        checkState(!result.containsKey(metadata.configKey()), "Overrides unsupported: %s",
            metadata.configKey());
        result.put(metadata.configKey(), metadata);
      }
      return new ArrayList<>(result.values());
    }

    /**
     * @deprecated use {@link #parseAndValidateMetadata(Class, Method)} instead.
     */
    @Deprecated
    public MethodMetadata parseAndValidateMetadata(Method method) {
      return parseAndValidateMetadata(method.getDeclaringClass(), method);
    }

    /**
     * Called indirectly by {@link #parseAndValidateMetadata(Class)}.
     */
    /**
     * 解析接口类中的方法
     * @param targetType  接口类
     * @param method  接口类中的方法
     * @return
     */
    protected MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
      final MethodMetadata data = new MethodMetadata();
      data.targetType(targetType);
      data.method(method);
      data.returnType(Types.resolve(targetType, targetType, method.getGenericReturnType()));
      data.configKey(Feign.configKey(targetType, method));

      if (targetType.getInterfaces().length == 1) {
        //处理父接口上的注解，留给子类实现
        processAnnotationOnClass(data, targetType.getInterfaces()[0]);
      }
      //处理当前接口上的注解，留给子类实现
      processAnnotationOnClass(data, targetType);


      for (final Annotation methodAnnotation : method.getAnnotations()) {
        //处理接口方法上的注解，留给子类实现
        processAnnotationOnMethod(data, methodAnnotation, method);
      }
      if (data.isIgnored()) {
        return data;
      }
      checkState(data.template().method() != null,
          "Method %s not annotated with HTTP method type (ex. GET, POST)%s",
          data.configKey(), data.warnings());
      //方法参数类型
      final Class<?>[] parameterTypes = method.getParameterTypes();
      final Type[] genericParameterTypes = method.getGenericParameterTypes();
      //参数注解
      final Annotation[][] parameterAnnotations = method.getParameterAnnotations();
      final int count = parameterAnnotations.length;
      for (int i = 0; i < count; i++) {
        boolean isHttpAnnotation = false;
        if (parameterAnnotations[i] != null) {
          //http相关的注解，留给子类实现
          isHttpAnnotation = processAnnotationsOnParameter(data, parameterAnnotations[i], i);
        }

        if (isHttpAnnotation) {
          data.ignoreParamater(i);
        }
        //参数类型是否是URI，有了就使用，没有就使用默认的
        if (parameterTypes[i] == URI.class) {
          data.urlIndex(i);
        } else if (!isHttpAnnotation && parameterTypes[i] != Request.Options.class) {
          if (data.isAlreadyProcessed(i)) {
            checkState(data.formParams().isEmpty() || data.bodyIndex() == null,
                "Body parameters cannot be used with form parameters.%s", data.warnings());
          } else {
            checkState(data.formParams().isEmpty(),
                "Body parameters cannot be used with form parameters.%s", data.warnings());
            checkState(data.bodyIndex() == null,
                "Method has too many Body parameters: %s%s", method, data.warnings());
            data.bodyIndex(i);
            data.bodyType(Types.resolve(targetType, targetType, genericParameterTypes[i]));
          }
        }
      }

      if (data.headerMapIndex() != null) {
        checkMapString("HeaderMap", parameterTypes[data.headerMapIndex()],
            genericParameterTypes[data.headerMapIndex()]);
      }

      if (data.queryMapIndex() != null) {
        if (Map.class.isAssignableFrom(parameterTypes[data.queryMapIndex()])) {
          checkMapKeys("QueryMap", genericParameterTypes[data.queryMapIndex()]);
        }
      }

      return data;
    }

    private static void checkMapString(String name, Class<?> type, Type genericType) {
      checkState(Map.class.isAssignableFrom(type),
          "%s parameter must be a Map: %s", name, type);
      checkMapKeys(name, genericType);
    }

    private static void checkMapKeys(String name, Type genericType) {
      Class<?> keyClass = null;

      // assume our type parameterized
      if (ParameterizedType.class.isAssignableFrom(genericType.getClass())) {
        final Type[] parameterTypes = ((ParameterizedType) genericType).getActualTypeArguments();
        keyClass = (Class<?>) parameterTypes[0];
      } else if (genericType instanceof Class<?>) {
        // raw class, type parameters cannot be inferred directly, but we can scan any extended
        // interfaces looking for any explict types
        final Type[] interfaces = ((Class) genericType).getGenericInterfaces();
        if (interfaces != null) {
          for (final Type extended : interfaces) {
            if (ParameterizedType.class.isAssignableFrom(extended.getClass())) {
              // use the first extended interface we find.
              final Type[] parameterTypes = ((ParameterizedType) extended).getActualTypeArguments();
              keyClass = (Class<?>) parameterTypes[0];
              break;
            }
          }
        }
      }

      if (keyClass != null) {
        checkState(String.class.equals(keyClass),
            "%s key must be a String: %s", name, keyClass.getSimpleName());
      }
    }

    /**
     * Called by parseAndValidateMetadata twice, first on the declaring class, then on the target
     * type (unless they are the same).
     *
     * @param data metadata collected so far relating to the current java method.
     * @param clz the class to process
     */
    /** * 解析类上的注解 */
    protected abstract void processAnnotationOnClass(MethodMetadata data, Class<?> clz);

    /**
     * @param data metadata collected so far relating to the current java method.
     * @param annotation annotations present on the current method annotation.
     * @param method method currently being processed.
     */
    /** * 解析方法上的注解 */
    protected abstract void processAnnotationOnMethod(MethodMetadata data,
                                                      Annotation annotation,
                                                      Method method);

    /**
     * @param data metadata collected so far relating to the current java method.
     * @param annotations annotations present on the current parameter annotation.
     * @param paramIndex if you find a name in {@code annotations}, call
     *        {@link #nameParam(MethodMetadata, String, int)} with this as the last parameter.
     * @return true if you called {@link #nameParam(MethodMetadata, String, int)} after finding an
     *         http-relevant annotation.
     */
    /** * 解析参数上的注解 */
    protected abstract boolean processAnnotationsOnParameter(MethodMetadata data,
                                                             Annotation[] annotations,
                                                             int paramIndex);

    /**
     * links a parameter name to its index in the method signature.
     */
    protected void nameParam(MethodMetadata data, String name, int i) {
      final Collection<String> names =
          data.indexToName().containsKey(i) ? data.indexToName().get(i) : new ArrayList<String>();
      names.add(name);
      data.indexToName().put(i, names);
    }
  }

  class Default extends DeclarativeContract {

    static final Pattern REQUEST_LINE_PATTERN = Pattern.compile("^([A-Z]+)[ ]*(.*)$");

    public Default() {
      //  @Headers	 类上或者方法上
      // 定义头部模板变量，使用@Param 注解提供参数值的注入。
      // 如果该注解添加在接口类上，则所有的请求都会携带对应的Header信息；如果在方法上，则只会添加到对应的方法请求上

      //类上支持 Headers 注解，当类上出现该注解
      super.registerClassAnnotation(Headers.class, (header, data) -> {
        final String[] headersOnType = header.value();
        checkState(headersOnType.length > 0, "Headers annotation was empty on type %s.",
            data.configKey());
        final Map<String, Collection<String>> headers = toMap(headersOnType);
        headers.putAll(data.template().headers());
        data.template().headers(null); // to clear
        //添加请求模板header
        data.template().headers(headers);
      });
      //@RequestLine    方法上
      //定义HttpMethod 和 UriTemplate. UriTemplate 中使用{} 包裹的表达式，可以通过在方法参数上使用@Param 自动注入

      //方法上支持 RequestLine 注解
      super.registerMethodAnnotation(RequestLine.class, (ann, data) -> {
        final String requestLine = ann.value();
        checkState(emptyToNull(requestLine) != null,
            "RequestLine annotation was empty on method %s.", data.configKey());

        final Matcher requestLineMatcher = REQUEST_LINE_PATTERN.matcher(requestLine);
        if (!requestLineMatcher.find()) {
          throw new IllegalStateException(String.format(
              "RequestLine annotation didn't start with an HTTP verb on method %s",
              data.configKey()));
        } else {
          //添加请求模板method
          data.template().method(HttpMethod.valueOf(requestLineMatcher.group(1)));
          //添加请求模板uri
          data.template().uri(requestLineMatcher.group(2));
        }
        data.template().decodeSlash(ann.decodeSlash());
        data.template()
            .collectionFormat(ann.collectionFormat());
      });
      //方法上支持 Body 注解
      super.registerMethodAnnotation(Body.class, (ann, data) -> {
        final String body = ann.value();
        checkState(emptyToNull(body) != null, "Body annotation was empty on method %s.",
            data.configKey());
        if (body.indexOf('{') == -1) {
          //添加请求模板body
          data.template().body(body);
        } else {
          //添加请求模板body
          data.template().bodyTemplate(body);
        }
      });
      //方法上支持 Headers 注解
      super.registerMethodAnnotation(Headers.class, (header, data) -> {
        final String[] headersOnMethod = header.value();
        checkState(headersOnMethod.length > 0, "Headers annotation was empty on method %s.",
            data.configKey());
        //添加请求模板header
        data.template().headers(toMap(headersOnMethod));
      });
      //@Param  方法参数
      //定义模板变量，模板变量的值可以使用名称的方式使用模板注入解析

      //参数上支持 Param 注解
      super.registerParameterAnnotation(Param.class, (paramAnnotation, data, paramIndex) -> {
        final String annotationName = paramAnnotation.value();
        final Parameter parameter = data.method().getParameters()[paramIndex];
        final String name;
        if (emptyToNull(annotationName) == null && parameter.isNamePresent()) {
          name = parameter.getName();
        } else {
          name = annotationName;
        }
        checkState(emptyToNull(name) != null, "Param annotation was empty on param %s.",
            paramIndex);
        //添加参数索引和参数名关系
        nameParam(data, name, paramIndex);
        final Class<? extends Param.Expander> expander = paramAnnotation.expander();
        if (expander != Param.ToStringExpander.class) {
          data.indexToExpanderClass().put(paramIndex, expander);
        }
        if (!data.template().hasRequestVariable(name)) {
          //添加form参数
          data.formParams().add(name);
        }
      });
      //@QueryMap	方法参数
      //定义一个键值对或者 pojo，参数值将会被转换成URL上的 query 字符串上

      //参数上支持 QueryMap 注解
      super.registerParameterAnnotation(QueryMap.class, (queryMap, data, paramIndex) -> {
        checkState(data.queryMapIndex() == null,
            "QueryMap annotation was present on multiple parameters.");
        data.queryMapIndex(paramIndex);
        data.queryMapEncoded(queryMap.encoded());
      });
      //参数上支持 HeaderMap 注解
      super.registerParameterAnnotation(HeaderMap.class, (queryMap, data, paramIndex) -> {
        checkState(data.headerMapIndex() == null,
            "HeaderMap annotation was present on multiple parameters.");
        //添加请求header参数索引
        data.headerMapIndex(paramIndex);
      });
    }

    private static Map<String, Collection<String>> toMap(String[] input) {
      final Map<String, Collection<String>> result =
          new LinkedHashMap<String, Collection<String>>(input.length);
      for (final String header : input) {
        final int colon = header.indexOf(':');
        final String name = header.substring(0, colon);
        if (!result.containsKey(name)) {
          result.put(name, new ArrayList<String>(1));
        }
        result.get(name).add(header.substring(colon + 1).trim());
      }
      return result;
    }

  }
}
