# tomcatXnetty


1.说明<br/>
&nbsp;&nbsp;&nbsp;&nbsp;基于 tomcat-embed 源码基础上修改，将底层 endpoint 使用 netty 实现，同时我按照自己的理解对原来的逻辑进行了大量修改，可以用于学习 tomcat 和 netty源码， <br/>
2.用法<br/>
&nbsp;&nbsp;&nbsp;&nbsp;在使用tomcat-embed的springboot 项目中新建如下配置类

```
@Component
public class WebServerFactoryCustomizer
		implements org.springframework.boot.web.server.WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

	@Override
	public void customize(TomcatServletWebServerFactory factory) {

		factory.setProtocol("org.apache.coyote.http11.Http11NettyProtocol");

	}

}
```
<br/>
欢迎讨论
