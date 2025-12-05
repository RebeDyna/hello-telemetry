# Use the official Python image from the Docker Hub
FROM python:3

# Set the working directory in the container
WORKDIR /usr/src/app

# Copy the requirements file into the container
COPY requirements.txt ./

# Install the required packages
RUN pip install --no-cache-dir -r requirements.txt

# Run opentelemetry'bootstrap
RUN opentelemetry-bootstrap -a install

# Copy the rest of the application code into the container
COPY . .

# Use Opentelemetry environment variables to enable logging. In development for Python
# ENV OTEL_PYTHON_LOGGING_AUTO_INSTRUMENTATION_ENABLED=true
# ENV OTEL_EXPORTER_OTLP_ENDPOINT=http://ht-otel-collector:4317

# Expose the port that the application will run on
EXPOSE 5000

# Define the command to run the application
# CMD [ "opentelemetry-instrument", "--service_name", "python-service", "python", "compute.py"]
CMD [ "python", "compute.py"]
