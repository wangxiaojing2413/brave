/*
 * Copyright 2013-2019 The OpenZipkin Authors
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
package brave.spring.beans;

import brave.Clock;
import brave.ErrorParser;
import brave.Tracing;
import brave.TracingCustomizer;
import brave.handler.FinishedSpanHandler;
import brave.propagation.CurrentTraceContext;
import brave.propagation.Propagation;
import brave.sampler.Sampler;
import java.util.List;
import org.springframework.beans.factory.config.AbstractFactoryBean;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.reporter.Reporter;

/** Spring XML config does not support chained builders. This converts accordingly */
public class TracingFactoryBean extends AbstractFactoryBean {

  String localServiceName;
  Endpoint localEndpoint, endpoint;
  Reporter<Span> spanReporter;
  List<FinishedSpanHandler> finishedSpanHandlers;
  Clock clock;
  Sampler sampler;
  ErrorParser errorParser;
  CurrentTraceContext currentTraceContext;
  Propagation.Factory propagationFactory;
  Boolean traceId128Bit;
  Boolean supportsJoin;
  List<TracingCustomizer> customizers;

  @Override protected Tracing createInstance() {
    Tracing.Builder builder = Tracing.newBuilder();
    if (localServiceName != null) builder.localServiceName(localServiceName);
    if (localEndpoint != null) builder.endpoint(localEndpoint);
    if (endpoint != null) builder.endpoint(endpoint);
    if (spanReporter != null) builder.spanReporter(spanReporter);
    if (finishedSpanHandlers != null) {
      for (FinishedSpanHandler finishedSpanHandler : finishedSpanHandlers) {
        builder.addFinishedSpanHandler(finishedSpanHandler);
      }
    }
    if (errorParser != null) builder.errorParser(errorParser);
    if (clock != null) builder.clock(clock);
    if (sampler != null) builder.sampler(sampler);
    if (currentTraceContext != null) builder.currentTraceContext(currentTraceContext);
    if (propagationFactory != null) builder.propagationFactory(propagationFactory);
    if (traceId128Bit != null) builder.traceId128Bit(traceId128Bit);
    if (supportsJoin != null) builder.supportsJoin(supportsJoin);
    if (customizers != null) {
      for (TracingCustomizer customizer : customizers) customizer.customize(builder);
    }
    return builder.build();
  }

  @Override protected void destroyInstance(Object instance) {
    ((Tracing) instance).close();
  }

  @Override public Class<? extends Tracing> getObjectType() {
    return Tracing.class;
  }

  @Override public boolean isSingleton() {
    return true;
  }

  public void setLocalServiceName(String localServiceName) {
    this.localServiceName = localServiceName;
  }

  public void setLocalEndpoint(Endpoint localEndpoint) {
    this.localEndpoint = localEndpoint;
  }

  public void setEndpoint(Endpoint endpoint) {
    this.endpoint = endpoint;
  }

  public void setSpanReporter(Reporter<Span> spanReporter) {
    this.spanReporter = spanReporter;
  }

  public List<FinishedSpanHandler> getFinishedSpanHandlers() {
    return finishedSpanHandlers;
  }

  public void setFinishedSpanHandlers(List<FinishedSpanHandler> finishedSpanHandlers) {
    this.finishedSpanHandlers = finishedSpanHandlers;
  }

  public void setClock(Clock clock) {
    this.clock = clock;
  }

  public void setErrorParser(ErrorParser errorParser) {
    this.errorParser = errorParser;
  }

  public void setSampler(Sampler sampler) {
    this.sampler = sampler;
  }

  public void setCurrentTraceContext(CurrentTraceContext currentTraceContext) {
    this.currentTraceContext = currentTraceContext;
  }

  public void setPropagationFactory(Propagation.Factory propagationFactory) {
    this.propagationFactory = propagationFactory;
  }

  public void setTraceId128Bit(boolean traceId128Bit) {
    this.traceId128Bit = traceId128Bit;
  }

  public void setSupportsJoin(Boolean supportsJoin) {
    this.supportsJoin = supportsJoin;
  }

  public void setCustomizers(List<TracingCustomizer> customizers) {
    this.customizers = customizers;
  }
}
