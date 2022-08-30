package org.apache.coyote;

public abstract class ProcessorComponent {

	protected final AbstractProcessor processor;

	public ProcessorComponent(AbstractProcessor processor) {
		super();
		this.processor = processor;
	}

}
