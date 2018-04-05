package brave;

import brave.Tracer.SpanInScope;
import brave.internal.HexCodec;
import brave.propagation.B3Propagation;
import brave.propagation.Propagation;
import brave.propagation.SamplingFlags;
import brave.propagation.StrictCurrentTraceContext;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.propagation.TraceIdContext;
import brave.sampler.Sampler;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import zipkin2.Endpoint;
import zipkin2.reporter.Reporter;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

public class TracerTest {
  List<zipkin2.Span> spans = new ArrayList<>();

  Tracer tracer = Tracing.newBuilder()
      .spanReporter(new Reporter<zipkin2.Span>() {
        @Override public void report(zipkin2.Span span) {
          spans.add(span);
        }

        @Override public String toString() {
          return "MyReporter{}";
        }
      })
      .currentTraceContext(new StrictCurrentTraceContext())
      .localEndpoint(Endpoint.newBuilder().serviceName("my-service").build())
      .build().tracer();

  @After public void close() {
    Tracing.current().close();
  }

  @Test public void sampler() {
    Sampler sampler = new Sampler() {
      @Override public boolean isSampled(long traceId) {
        return false;
      }
    };

    tracer = Tracing.newBuilder().sampler(sampler).build().tracer();

    assertThat(tracer.sampler)
        .isSameAs(sampler);
  }

  @Test public void withSampler() {
    Sampler sampler = new Sampler() {
      @Override public boolean isSampled(long traceId) {
        return false;
      }
    };

    tracer = tracer.withSampler(sampler);

    assertThat(tracer.sampler)
        .isSameAs(sampler);
  }

  @Test public void localServiceName() {
    tracer = Tracing.newBuilder().localServiceName("my-foo").build().tracer();

    assertThat(tracer).extracting("recorder.spanMap.localEndpoint.serviceName")
        .containsExactly("my-foo");
  }

  @Test public void localServiceName_defaultIsUnknown() {
    tracer = Tracing.newBuilder().build().tracer();

    assertThat(tracer).extracting("recorder.spanMap.localEndpoint.serviceName")
        .containsExactly("unknown");
  }

  @Test public void localServiceName_ignoredWhenGivenLocalEndpoint() {
    Endpoint localEndpoint = Endpoint.newBuilder().serviceName("my-bar").build();
    tracer = Tracing.newBuilder().localServiceName("my-foo")
        .localEndpoint(localEndpoint).build().tracer();

    assertThat(tracer).extracting("recorder.spanMap.localEndpoint")
        .containsExactly(localEndpoint);
  }

  @Test public void clock() {
    Clock clock = () -> 0L;
    tracer = Tracing.newBuilder().clock(clock).build().tracer();

    assertThat(tracer.clock())
        .isSameAs(clock);
  }

  @Test public void newTrace_isRootSpan() {
    assertThat(tracer.newTrace())
        .satisfies(s -> assertThat(s.context().parentId()).isNull())
        .isInstanceOf(RealSpan.class);
  }

  @Test public void newTrace_traceId128Bit() {
    tracer = Tracing.newBuilder().traceId128Bit(true).build().tracer();

    assertThat(tracer.newTrace().context().traceIdHigh())
        .isNotZero();
  }

  @Test public void newTrace_unsampled_tracer() {
    tracer = tracer.withSampler(Sampler.NEVER_SAMPLE);

    assertThat(tracer.newTrace())
        .isInstanceOf(NoopSpan.class);
  }

  @Test public void newTrace_sampled_flag() {
    assertThat(tracer.newTrace(SamplingFlags.SAMPLED))
        .isInstanceOf(RealSpan.class);
  }

  @Test public void newTrace_noop_on_sampled_flag() {
    tracer.noop.set(true);

    assertThat(tracer.newTrace(SamplingFlags.SAMPLED))
        .isInstanceOf(NoopSpan.class);
  }

  @Test public void newTrace_debug_flag() {
    Span root = tracer.newTrace(SamplingFlags.DEBUG).start();
    root.finish();

    assertThat(spans).extracting(zipkin2.Span::debug)
        .containsExactly(true);
  }

  @Test public void newTrace_noop_on_debug_flag() {
    tracer.noop.set(true);

    assertThat(tracer.newTrace(SamplingFlags.DEBUG))
        .isInstanceOf(NoopSpan.class);
  }

  @Test public void newTrace_notsampled_flag() {
    assertThat(tracer.newTrace(SamplingFlags.NOT_SAMPLED))
        .isInstanceOf(NoopSpan.class);
  }

  /** When we join a sampled request, we are sharing the same trace identifiers. */
  @Test public void join_setsShared() {
    TraceContext fromIncomingRequest = tracer.newTrace().context();

    tracer.joinSpan(fromIncomingRequest).start().finish();
    assertThat(spans.get(0).shared())
        .isTrue();
  }

  @Test public void join_createsChildWhenUnsupported() {
    tracer = Tracing.newBuilder().supportsJoin(false).spanReporter(spans::add).build().tracer();

    TraceContext fromIncomingRequest = tracer.newTrace().context();

    tracer.joinSpan(fromIncomingRequest).start().finish();
    assertThat(spans.get(0).shared())
        .isNull();
    assertThat(spans.get(0).parentId())
        .isEqualTo(HexCodec.toLowerHex(fromIncomingRequest.spanId()));
  }

  @Test public void join_createsChildWhenUnsupportedByPropagation() {
    tracer = Tracing.newBuilder()
        .propagationFactory(new Propagation.Factory() {
          @Override public <K> Propagation<K> create(Propagation.KeyFactory<K> keyFactory) {
            return B3Propagation.FACTORY.create(keyFactory);
          }
        })
        .spanReporter(spans::add).build().tracer();

    TraceContext fromIncomingRequest = tracer.newTrace().context();

    tracer.joinSpan(fromIncomingRequest).start().finish();
    assertThat(spans.get(0).shared())
        .isNull();
    assertThat(spans.get(0).parentId())
        .isEqualTo(HexCodec.toLowerHex(fromIncomingRequest.spanId()));
  }

  @Test public void join_noop() {
    TraceContext fromIncomingRequest = tracer.newTrace().context();

    tracer.noop.set(true);

    assertThat(tracer.joinSpan(fromIncomingRequest))
        .isInstanceOf(NoopSpan.class);
  }

  @Test public void join_ensuresSampling() {
    TraceContext notYetSampled =
        tracer.newTrace().context().toBuilder().sampled(null).build();

    assertThat(tracer.joinSpan(notYetSampled).context())
        .isEqualTo(notYetSampled.toBuilder().sampled(true).build());
  }

  @Test public void newChild_ensuresSampling() {
    TraceContext notYetSampled =
        tracer.newTrace().context().toBuilder().sampled(null).build();

    assertThat(tracer.newChild(notYetSampled).context().sampled())
        .isTrue();
  }

  @Test public void nextSpan_ensuresSampling_whenCreatingNewChild() {
    TraceContext notYetSampled =
        tracer.newTrace().context().toBuilder().sampled(null).build();

    TraceContextOrSamplingFlags extracted = TraceContextOrSamplingFlags.create(notYetSampled);
    assertThat(tracer.nextSpan(extracted).context().sampled())
        .isTrue();
  }

  @Test public void toSpan() {
    TraceContext context = tracer.newTrace().context();

    assertThat(tracer.toSpan(context))
        .isInstanceOf(RealSpan.class)
        .extracting(Span::context)
        .containsExactly(context);
  }

  @Test public void toSpan_noop() {
    TraceContext context = tracer.newTrace().context();

    tracer.noop.set(true);

    assertThat(tracer.toSpan(context))
        .isInstanceOf(NoopSpan.class);
  }

  @Test public void toSpan_unsampledIsNoop() {
    TraceContext unsampled =
        tracer.newTrace().context().toBuilder().sampled(false).build();

    assertThat(tracer.toSpan(unsampled))
        .isInstanceOf(NoopSpan.class);
  }

  @Test public void newChild() {
    TraceContext parent = tracer.newTrace().context();

    assertThat(tracer.newChild(parent))
        .satisfies(c -> {
          assertThat(c.context().traceIdString()).isEqualTo(parent.traceIdString());
          assertThat(c.context().parentId()).isEqualTo(parent.spanId());
        })
        .isInstanceOf(RealSpan.class);
  }

  /** A child span is not sharing a span ID with its parent by definition */
  @Test public void newChild_isntShared() {
    tracer.newTrace().start().finish();

    assertThat(spans.get(0).shared())
        .isNull();
  }

  @Test public void newChild_noop() {
    TraceContext parent = tracer.newTrace().context();

    tracer.noop.set(true);

    assertThat(tracer.newChild(parent))
        .isInstanceOf(NoopSpan.class);
  }

  @Test public void newChild_unsampledIsNoop() {
    TraceContext unsampled =
        tracer.newTrace().context().toBuilder().sampled(false).build();

    assertThat(tracer.newChild(unsampled))
        .isInstanceOf(NoopSpan.class);
  }

  @Test public void currentSpan_defaultsToNull() {
    assertThat(tracer.currentSpan()).isNull();
  }

  @Test public void nextSpan_defaultsToMakeNewTrace() {
    assertThat(tracer.nextSpan().context().parentId()).isNull();
  }

  @Test public void nextSpan_extractedNothing_makesChildOfCurrent() {
    Span parent = tracer.newTrace();

    try (SpanInScope ws = tracer.withSpanInScope(parent)) {
      Span nextSpan = tracer.nextSpan(TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY));
      assertThat(nextSpan.context().parentId())
          .isEqualTo(parent.context().spanId());
    }
  }

  @Test public void nextSpan_extractedNothing_defaultsToMakeNewTrace() {
    Span nextSpan = tracer.nextSpan(TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY));

    assertThat(nextSpan.context().parentId())
        .isNull();
  }

  @Test public void nextSpan_makesChildOfCurrent() {
    Span parent = tracer.newTrace();

    try (SpanInScope ws = tracer.withSpanInScope(parent)) {
      assertThat(tracer.nextSpan().context().parentId())
          .isEqualTo(parent.context().spanId());
    }
  }

  @Test public void nextSpan_extractedExtra_newTrace() {
    TraceContextOrSamplingFlags extracted =
        TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY).toBuilder().addExtra(1L).build();

    assertThat(tracer.nextSpan(extracted).context().extra())
        .containsExactly(1L);
  }

  @Test public void nextSpan_extractedExtra_childOfCurrent() {
    Span parent = tracer.newTrace();

    TraceContextOrSamplingFlags extracted =
        TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY).toBuilder().addExtra(1L).build();

    try (SpanInScope ws = tracer.withSpanInScope(parent)) {
      assertThat(tracer.nextSpan(extracted).context().extra())
          .containsExactly(1L);
    }
  }

  @Test public void nextSpan_extractedExtra_appendsToChildOfCurrent() {
    // current parent already has extra stuff
    Span parent = tracer.toSpan(tracer.newTrace().context().toBuilder().extra(asList(1L)).build());

    TraceContextOrSamplingFlags extracted =
        TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY).toBuilder().addExtra(2L).build();

    try (SpanInScope ws = tracer.withSpanInScope(parent)) {
      assertThat(tracer.nextSpan(extracted).context().extra())
          .containsExactly(1L, 2L);
    }
  }

  @Test public void nextSpan_extractedTraceId() {
    TraceIdContext traceIdContext = TraceIdContext.newBuilder().traceId(1L).build();
    TraceContextOrSamplingFlags extracted = TraceContextOrSamplingFlags.create(traceIdContext);

    assertThat(tracer.nextSpan(extracted).context().traceId())
        .isEqualTo(1L);
  }

  @Test public void nextSpan_extractedTraceId_extra() {
    TraceIdContext traceIdContext = TraceIdContext.newBuilder().traceId(1L).build();
    TraceContextOrSamplingFlags extracted = TraceContextOrSamplingFlags.create(traceIdContext)
        .toBuilder().addExtra(1L).build();

    assertThat(tracer.nextSpan(extracted).context().extra())
        .containsExactly(1L);
  }

  @Test public void nextSpan_extractedTraceContext() {
    TraceContext traceContext = TraceContext.newBuilder().traceId(1L).spanId(2L).build();
    TraceContextOrSamplingFlags extracted = TraceContextOrSamplingFlags.create(traceContext);

    assertThat(tracer.nextSpan(extracted).context())
        .extracting(TraceContext::traceId, TraceContext::parentId)
        .containsExactly(1L, 2L);
  }

  @Test public void nextSpan_extractedTraceContext_extra() {
    TraceContext traceContext = TraceContext.newBuilder().traceId(1L).spanId(2L).build();
    TraceContextOrSamplingFlags extracted = TraceContextOrSamplingFlags.create(traceContext)
        .toBuilder().addExtra(1L).build();

    assertThat(tracer.nextSpan(extracted).context().extra())
        .containsExactly(1L);
  }

  @Test public void withSpanInScope() {
    Span current = tracer.newTrace();

    try (SpanInScope ws = tracer.withSpanInScope(current)) {
      assertThat(tracer.currentSpan())
          .isEqualTo(current);
    }

    // context was cleared
    assertThat(tracer.currentSpan()).isNull();
  }

  @Test public void toString_withSpanInScope() {
    TraceContext context = TraceContext.newBuilder().traceId(1L).spanId(10L).build();
    try (SpanInScope ws = tracer.withSpanInScope(tracer.toSpan(context))) {
      assertThat(tracer.toString()).hasToString(
          "Tracer{currentSpan=0000000000000001/000000000000000a, reporter=MyReporter{}}"
      );
    }
  }

  @Test public void toString_withSpanInFlight() {
    TraceContext context = TraceContext.newBuilder().traceId(1L).spanId(10L).sampled(true).build();
    Span span = tracer.toSpan(context);
    span.start(1L); // didn't set anything else! this is to help ensure no NPE

    assertThat(tracer).hasToString(
        "Tracer{inFlight=[{\"traceId\":\"0000000000000001\",\"id\":\"000000000000000a\",\"timestamp\":1,\"localEndpoint\":{\"serviceName\":\"my-service\"}}], reporter=MyReporter{}}"
    );

    span.finish();

    assertThat(tracer).hasToString(
        "Tracer{reporter=MyReporter{}}"
    );
  }

  @Test public void toString_whenNoop() {
    Tracing.current().setNoop(true);

    assertThat(tracer).hasToString(
        "Tracer{noop=true, reporter=MyReporter{}}"
    );
  }

  @Test public void withSpanInScope_nested() {
    Span parent = tracer.newTrace();

    try (SpanInScope wsParent = tracer.withSpanInScope(parent)) {

      Span child = tracer.newChild(parent.context());
      try (SpanInScope wsChild = tracer.withSpanInScope(child)) {
        assertThat(tracer.currentSpan())
            .isEqualTo(child);
      }

      // old parent reverted
      assertThat(tracer.currentSpan())
          .isEqualTo(parent);
    }
  }

  @Test public void withSpanInScope_clear() {
    Span parent = tracer.newTrace();

    try (SpanInScope wsParent = tracer.withSpanInScope(parent)) {
      try (SpanInScope clearScope = tracer.withSpanInScope(null)) {
        assertThat(tracer.currentSpan())
            .isNull();
      }

      // old parent reverted
      assertThat(tracer.currentSpan())
          .isEqualTo(parent);
    }
  }
}
