# tomcatXnetty
将tomcat底层endpoint替换成了netty，仅供学习tomcat和netty
网络原因，代码稍后上传
用法 springboot项目中新建如下配置类
@Component
public class WebServerFactoryCustomizer
		implements org.springframework.boot.web.server.WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

	@Override
	public void customize(TomcatServletWebServerFactory factory) {

		factory.setProtocol("org.apache.coyote.http11.Http11NettyProtocol");

	}

}