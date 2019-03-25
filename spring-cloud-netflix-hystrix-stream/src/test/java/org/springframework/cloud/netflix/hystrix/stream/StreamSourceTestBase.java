/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.hystrix.stream;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.circuitbreaker.EnableCircuitBreaker;
import org.springframework.cloud.contract.verifier.messaging.MessageVerifier;
import org.springframework.cloud.contract.verifier.messaging.boot.AutoConfigureMessageVerifier;
import org.springframework.cloud.contract.verifier.messaging.stream.StreamStubMessages;
import org.springframework.cloud.netflix.hystrix.contract.HystrixContractUtils;
import org.springframework.cloud.netflix.hystrix.stream.StreamSourceTestBase.TestApplication;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.test.binder.MessageCollector;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.DefaultContentTypeResolver;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Base class for sensor autogenerated tests (used by Spring Cloud Contract).
 *
 * This bootstraps the Spring Boot application code.
 *
 * @author Marius Bogoevici
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class, properties = {
		"spring.application.name=application" })
@AutoConfigureMessageVerifier
public abstract class StreamSourceTestBase {

	@Autowired
	TestApplication application;

	public void createMetricsData() throws Exception {
		application.hello();
	}

	public void assertOrigin(Object input) {
		System.err.println(input);
		@SuppressWarnings("unchecked")
		Map<String, Object> origin = (Map<String, Object>) input;
		HystrixContractUtils.checkOrigin(origin);
	}

	public void assertData(Object input) {
		// System.err.println(input);
		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) input;
		HystrixContractUtils.checkData(data, TestApplication.class.getSimpleName(),
				"application.hello");
	}

	public void assertEvent(Object input) {
		HystrixContractUtils.checkEvent((String) input);
	}

	@EnableAutoConfiguration
	@EnableCircuitBreaker
	@RestController
	public static class TestApplication {

		@HystrixCommand
		@RequestMapping("/")
		public String hello() {
			return "Hello World";
		}

		public static void main(String[] args) {
			SpringApplication.run(TestApplication.class, args);
		}

		// TODO: remove this as soon as contract 2.0.0 is available
		@Bean
		MessageVerifier<Message<?>> contractVerifierMessageExchange(
				ApplicationContext applicationContext) {
			return new PatchedStubMessages(applicationContext);
		}

	}

	static class PatchedStubMessages implements MessageVerifier<Message<?>> {

		private static final Logger log = LoggerFactory
				.getLogger(StreamStubMessages.class);

		private final ApplicationContext context;

		private final MessageCollector messageCollector;

		private final ContractVerifierStreamMessageBuilder builder = new ContractVerifierStreamMessageBuilder();

		PatchedStubMessages(ApplicationContext context) {
			this.context = context;
			this.messageCollector = context.getBean(MessageCollector.class);
		}

		@Override
		public <T> void send(T payload, Map<String, Object> headers, String destination) {
			send(this.builder.create(payload, headers), destination);
		}

		@Override
		public void send(Message<?> message, String destination) {
			try {
				MessageChannel messageChannel = this.context
						.getBean(resolvedDestination(destination), MessageChannel.class);
				messageChannel.send(message);
			}
			catch (Exception e) {
				log.error(
						"Exception occurred while trying to send a message [" + message
								+ "] " + "to a channel with name [" + destination + "]",
						e);
				throw e;
			}
		}

		@Override
		public Message<?> receive(String destination, long timeout, TimeUnit timeUnit) {
			try {
				MessageChannel messageChannel = this.context
						.getBean(resolvedDestination(destination), MessageChannel.class);
				Message<?> message = this.messageCollector.forChannel(messageChannel)
						.poll(timeout, timeUnit);
				if (message == null) {
					return message;
				}
				return MessageBuilder.createMessage(message.getPayload(),
						message.getHeaders());
			}
			catch (Exception e) {
				log.error("Exception occurred while trying to read a message from "
						+ " a channel with name [" + destination + "]", e);
				throw new IllegalStateException(e);
			}
		}

		private String resolvedDestination(String destination) {
			try {
				BindingServiceProperties channelBindingServiceProperties = this.context
						.getBean(BindingServiceProperties.class);
				for (Map.Entry<String, BindingProperties> entry : channelBindingServiceProperties
						.getBindings().entrySet()) {
					if (destination.equals(entry.getValue().getDestination())) {
						if (log.isDebugEnabled()) {
							log.debug("Found a channel named [{}] with destination [{}]",
									entry.getKey(), destination);
						}
						return entry.getKey();
					}
				}
			}
			catch (Exception e) {
				log.error(
						"Exception took place while trying to resolve the destination. Will assume the name ["
								+ destination + "]",
						e);
			}
			if (log.isDebugEnabled()) {
				log.debug("No destination named [" + destination
						+ "] was found. Assuming that the destination equals the channel name",
						destination);
			}
			return destination;
		}

		@Override
		public Message<?> receive(String destination) {
			return receive(destination, 5, TimeUnit.SECONDS);
		}

		private MappingJackson2MessageConverter converter() {
			ObjectMapper mapper = null;
			try {
				mapper = this.context.getBean(ObjectMapper.class);
			}
			catch (NoSuchBeanDefinitionException e) {

			}
			MappingJackson2MessageConverter converter = createJacksonConverter();
			if (mapper != null) {
				converter.setObjectMapper(mapper);
			}
			return converter;
		}

		protected MappingJackson2MessageConverter createJacksonConverter() {
			DefaultContentTypeResolver resolver = new DefaultContentTypeResolver();
			resolver.setDefaultMimeType(MimeTypeUtils.APPLICATION_JSON);
			MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
			converter.setContentTypeResolver(resolver);
			return converter;
		}

	}

	static class ContractVerifierStreamMessageBuilder {

		public <T> Message<?> create(T payload, Map<String, Object> headers) {
			return MessageBuilder.createMessage(payload, new MessageHeaders(headers));
		}

	}

}
