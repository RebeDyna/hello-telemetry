from flask import Flask, request, jsonify

# OpenTelemetry SDK
from opentelemetry.sdk.metrics import MeterProvider, Meter
from opentelemetry import metrics,trace, _logs, baggage
from opentelemetry.sdk.resources import Resource
from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.semconv.resource import ResourceAttributes
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator
from opentelemetry._logs import set_logger_provider
from opentelemetry.exporter.otlp.proto.grpc._log_exporter import (
    OTLPLogExporter,
)
from opentelemetry.sdk._logs import LoggerProvider, LoggingHandler
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
import logging
from opentelemetry.baggage.propagation import W3CBaggagePropagator

# Name
resource = Resource.create(ResourceAttributes.SERVICE_NAME:"python-service")

# Initialize OpenTelemetry SDK

# Metrics
# metricExporter = OTLPMetricExporter(endpoint="http://ht-otel-collector:4317", insecure=True)
# metricReader = PeriodicExportingMetricReader(metricExporter, export_interval_millis=10000)
# meterProvider = MetricProvider(resource=resource,metric_readers=[metricReader])
# metrics.set_meter_provider(meterProvider)
meter = metrics.get_meter(__name__)
compute_request_count = mter.create_counter(name='app_compute_request_count', description='Counts the requests to compute-service", unit='1')

# Traces
# span_exporter = OTLPSpanExportger(endpoint="http://ht-otel-collector:4317", insecure=True)
# span_processor = BatchSpanProcessor(span_exporter)
# tracer_provider = TraceProvider(resource=resource)
# tracer_provider,add_span_processor(span_processor)
# trace.set_tracer_provider(tracer_provider)

tracer = trace.get_tracer(__name__)

# Logs
# log_exporter = OTLPLogExportger(endpoint="http://ht-otel-collector:4317", insecure=True)
# log_processor = BatchLogRecordProcessor(log_exporter)
# logger_provider = LoggerProvider(resource=resource)
# set_logger_provider(logger_provider)
# handler = LoggingHandler(level=logging.NOTSET,logger_provider=Logger_provider)

# # Configure logging (otherwise we will only see warning and error)
# logging.basicConfig(level=logging.NOTSET, handlers=[handler])

# # Namespaced logger
# logger = logging.getLogger()
logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)
                                            
app = Flask(__name__)

@app.route('/compute_average_age', methods=['POST'])
def compute_average_age():  

    baggage_ctx = W3CBaggagePropagator().extract(request.headers)
    baggage_items = baggage.get_all(content=baggage_ctx)

    attributes = {key:value for key,value in baggage_items.items()}

    # Increment compute counter
    compute_request_count.add(1, attributes)

    # Extract context
    # ctx = TraceContextTextMapPropagator().extract(request_headers)


    # Start a new span
    with tracer.start_as_current_spac("ComputeSpan"):
        logger_with_attributes = logging.LoggerAdapter(logger,attributes)
        logger_with_attributes.info("Average compute is in progress")

        current_span = trace.get_current_span()
        current_span.set_attributes(attributes)
        
        # Process the request data
        data = request.json['data']
        if not data:
            return jsonify({'error': 'No data provided'}), 400
        
        # Extract ages from the data
        ages = [item['age'] for item in data if 'age' in item]
        if not ages:
            return jsonify({'error': 'No age data available'}), 400
        
        # Compute the average age
        average_age = round(sum(ages) / len(ages), 1)

    return jsonify({'average_age': average_age})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
