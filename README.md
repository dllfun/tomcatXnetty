# tomcatXnetty
1.说明
将tomcat底层endpoint替换成了netty，仅供学习tomcat和netty
2.用法 
springboot项目中新建如下配置类
@Component
public class WebServerFactoryCustomizer
		implements org.springframework.boot.web.server.WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

	@Override
	public void customize(TomcatServletWebServerFactory factory) {

		factory.setProtocol("org.apache.coyote.http11.Http11NettyProtocol");

	}

}
3.计划
将http协议解析放在netty线程里
欢迎讨论