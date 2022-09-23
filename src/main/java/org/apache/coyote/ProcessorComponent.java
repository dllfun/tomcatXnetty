package org.apache.coyote;

import org.apache.tomcat.util.net.Channel;

public abstract class ProcessorComponent {

	protected final AbstractProcessor processor;

	public ProcessorComponent(AbstractProcessor processor) {
		this.processor = processor;
		this.processor.addComponent(this);
	}

	public void onChannelReady(Channel channel) {

	}

}
