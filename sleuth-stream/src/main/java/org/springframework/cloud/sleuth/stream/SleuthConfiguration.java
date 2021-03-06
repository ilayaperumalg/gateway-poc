package org.springframework.cloud.sleuth.stream;

import java.util.ArrayList;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cloud.sleuth.Sampler;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.SpanExtractor;
import org.springframework.cloud.sleuth.SpanInjector;
import org.springframework.cloud.sleuth.SpanReporter;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.cloud.sleuth.sampler.AlwaysSampler;
import org.springframework.cloud.stream.binding.CompositeMessageChannelConfigurer;
import org.springframework.cloud.stream.binding.MessageChannelConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;
import org.springframework.integration.channel.ChannelInterceptorAware;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptorAdapter;
import org.springframework.messaging.support.MessageBuilder;

/**
 * @author Marius Bogoevici
 */
@Configuration
@PropertySource("classpath:META-INF/spring-sleuth-defaults.properties")
public class SleuthConfiguration {

	@Bean
	@Primary
	public Sampler defaultSampler() {
		return new AlwaysSampler();
	}

	@Bean
	public static BeanPostProcessor postProcessMessageChannelConfigurer(@Lazy final SpanInjector<MessageBuilder<?>> spanInjector,
																		@Lazy final SpanExtractor<Message<?>> spanExtractor,
																		@Lazy final Tracer tracer,
																		@Lazy final SpanReporter spanReporter) {

		return new BeanPostProcessor() {
			@Override
			public Object postProcessBeforeInitialization(Object o, String s) throws BeansException {
				// Work around https://github.com/spring-cloud/spring-cloud-stream/issues/705
				if (o instanceof CompositeMessageChannelConfigurer) {
					ArrayList<MessageChannelConfigurer> configurers = new ArrayList<>();
					configurers.add((MessageChannelConfigurer) o);
					configurers.add(sleuthChannelConfigurer(spanInjector, spanExtractor, tracer, spanReporter));
					return new CompositeMessageChannelConfigurer(configurers);
				}
				return o;
			}

			@Override
			public Object postProcessAfterInitialization(Object o, String s) throws BeansException {
				return o;
			}
		};
	}

	private static MessageChannelConfigurer sleuthChannelConfigurer(final SpanInjector<MessageBuilder<?>> spanInjector,
																	final SpanExtractor<Message<?>> spanExtractor,
																	final Tracer tracer,
																	final SpanReporter spanReporter) {
		return new MessageChannelConfigurer() {
			@Override
			public void configureInputChannel(MessageChannel messageChannel,
					String channelName) {
				if (messageChannel instanceof ChannelInterceptorAware && !"sleuth".equals(channelName)) {
					((ChannelInterceptorAware) messageChannel)
							.addInterceptor(new InputChannelSleuthInterceptorAdapter(spanInjector, spanExtractor, tracer, spanReporter));
				}
			}

			@Override
			public void configureOutputChannel(MessageChannel messageChannel,
					String channelName) {
				if (messageChannel instanceof ChannelInterceptorAware && !"sleuth".equals(channelName)) {
					((ChannelInterceptorAware) messageChannel)
							.addInterceptor(new OutputChannelSleuthInterceptorAdapter(spanInjector, spanExtractor, tracer, spanReporter));
				}
			}
		};
	}

	private static class InputChannelSleuthInterceptorAdapter extends ChannelInterceptorAdapter {
		
		private SpanInjector<MessageBuilder<?>> spanInjector;
		
		private SpanExtractor<Message<?>> spanExtractor;
		
		private Tracer tracer;
		
		private SpanReporter spanReporter;

		public InputChannelSleuthInterceptorAdapter(
				SpanInjector<MessageBuilder<?>> spanInjector,
				SpanExtractor<Message<?>> spanExtractor, Tracer tracer,
				SpanReporter spanReporter) {
			this.spanInjector = spanInjector;
			this.spanExtractor = spanExtractor;
			this.tracer = tracer;
			this.spanReporter = spanReporter;
		}

		@Override
		public Message<?> preSend(Message<?> message, MessageChannel channel) {
			if (message.getHeaders().containsKey("parent-span-id")) {

				Span brokerSpan = spanExtractor.joinTrace(message);
				brokerSpan.logEvent("broker.received");
				tracer.close(brokerSpan);
				spanReporter.report(brokerSpan);

				Span rootSpan = Span.builder()
						.spanId(message.getHeaders().get("parent-span-id", Long.class))
						.traceId(message.getHeaders().get("parent-trace-id", Long.class))
						.name(message.getHeaders().get("parent-span-name", String.class))
						.build();
				rootSpan.logEvent("stream.receivedFromBroker");
				tracer.close(rootSpan);
				spanReporter.report(rootSpan);

				Span processSpan = tracer.createSpan("process", rootSpan);
			}
			return message;
		}
	}

	private static class OutputChannelSleuthInterceptorAdapter
			extends ChannelInterceptorAdapter {

		private SpanInjector<MessageBuilder<?>> spanInjector;

		private SpanExtractor<Message<?>> spanExtractor;

		private Tracer tracer;

		private SpanReporter spanReporter;

		public OutputChannelSleuthInterceptorAdapter(
				SpanInjector<MessageBuilder<?>> spanInjector,
				SpanExtractor<Message<?>> spanExtractor, Tracer tracer,
				SpanReporter spanReporter) {
			this.spanInjector = spanInjector;
			this.spanExtractor = spanExtractor;
			this.tracer = tracer;
			this.spanReporter = spanReporter;
		}

		@Override
		public Message<?> preSend(Message<?> message, MessageChannel channel) {
			if (message.getHeaders().containsKey("parent-span-id")) {
				Span currentSpan = tracer.getCurrentSpan();
				tracer.close(currentSpan);
				spanReporter.report(currentSpan);

				Span rootSpan = Span.builder()
						.spanId(message.getHeaders().get("parent-span-id", Long.class))
						.traceId(message.getHeaders().get("parent-trace-id", Long.class))
						.name(message.getHeaders().get("parent-span-name", String.class))
						.build();
				rootSpan.logEvent("stream.sentToBroker");
				tracer.close(rootSpan);
				spanReporter.report(rootSpan);

				Span brokerSpan = tracer.createSpan("broker", rootSpan);
				brokerSpan.logEvent("broker.sent");
				MessageBuilder<?> carrier = MessageBuilder.fromMessage(message);
				carrier.setHeader("parent-span-id", rootSpan.getSpanId());
				carrier.setHeader("parent-trace-id", rootSpan.getTraceId());
				carrier.setHeader("parent-span-name", rootSpan.getName());
				spanInjector.inject(brokerSpan, carrier);
				tracer.close(brokerSpan);
				spanReporter.report(brokerSpan);

				return carrier.build();
			}
			else {
				MessageBuilder<?> builder = MessageBuilder.fromMessage(message);
				Span streamSpan = tracer.createSpan("stream");
				streamSpan.logEvent("stream.start");

				Span brokerSpan = tracer.createSpan("broker", streamSpan);
				brokerSpan.logEvent("broker.sent");
				spanInjector.inject(brokerSpan, builder);
				builder.setHeader("parent-span-id", streamSpan.getSpanId());
				builder.setHeader("parent-trace-id", streamSpan.getTraceId());
				builder.setHeader("parent-span-name", streamSpan.getName());
				tracer.close(brokerSpan);
				spanReporter.report(brokerSpan);
				tracer.close(streamSpan);
				spanReporter.report(streamSpan);
				return builder.build();
			}
		}
	}
}
