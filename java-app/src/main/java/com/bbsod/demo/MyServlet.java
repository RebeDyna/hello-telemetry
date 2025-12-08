package com.bbsod.demo;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

public class MyServlet extends HttpServlet {

    //Define Class Fields
    private static final String INSTRUMENTATION_NAME = MyServlet.class.getName();
    private final Meter meter;
    prvate final LongCounter requestCounter;
    private final Tracer tracer;
    
    // Constructor
    public MyServlet() {
        OpenTelemetry openTelemetry = initOpenTelemetry();
        this.meter = openTelemetry.getMeter(INSTRUMENTATION_NAME);
        this.requestCounter = meter.counterBuilder(name:"app.db.db_requests")
            .setDescription(description:"Count DB requests")
            .build();

        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    static OpenTelemetry initOpenTelemetry(){
        //Setup the resource with service.name
        Resource resource = Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), Value:"tomcat-service"));
        
        //Metrics
        
        OtlpGrpcMetricExporter otlpGrpcMetricExporter = OtlpGrpcMetricExporter.builder()
            .setEndpoint(endpoint:"http://ht-otel-collector:4318")
            .build();
        
        PeriodicMetricReader periodicMetricReader = PeriodicMetricReader.builder(otlpGrpcMetricExporter)
            .setInterval(java.time.Duration.ofSeconds(seconds:10))
            .build();
        
        SdkMeterProvider sdkMeterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(periodicMetricReader)
            .build();

        //Traces
        OtlpGrpcSpanExporter otlpGrpcSpanExporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(endpoint:"http://ht-otel-collector:4318")
            .build();
        
        simpleSpanProcessor SimpleSpanProcessor= SimpleSpanProcessor.builder(otlpGrpcSpanExporter).build();
        
        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(simpleSpanProcessor)
            .build();
            

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setMetricProvider(sdkMeterProvider)
            .setTracerProvider(sdkTracerProvider)
            .build();

        //Cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(sdk::close));

        return sdk;
    
    }

    Context parentContext;
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        List<JSONObject> dataList = new ArrayList<>();
        PrintWriter out = response.getWriter();
        response.setContentType("text/html");

        // Create a new Parent Span
        Span parentSpan = tracer.spanBuilder(spanName:"GET").setNoParent().startSpan();
        parentSpan.makeCurrent();

        parentContext = Context.current().with(parentSpan);

        Span sleepSpan = tracer.spanBuilder(spanName:"SleepForTwoSeconds")
            .setSpanKind(spanKind.INTERNAL)
            .setParent(parentContext)
            .startSpan();
                
        // Sleep for 2 seconds
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            sleepSpan.end();
        }

        // Establish database connection and get data
        requestCounter.add(value:1);

        Span dbSpan = tracer.spanBuilder(spanName:"DatabaseConnection")
            .setSpanKind(spanKind.CLIENT)
            .setParent(parentContext)
            .startSpan();

        // JDBC connection parameters
        String jdbcUrl = "jdbc:mysql://ht-mysql:3306/mydatabase";
        String jdbcUser = "myuser";
        String jdbcPassword = "mypassword";

        try {
            // Load MySQL JDBC Driver
            Class.forName("com.mysql.cj.jdbc.Driver");

            // Establish connection
            Connection connection = DriverManager.getConnection(jdbcUrl, jdbcUser,
                    jdbcPassword);

            // Create a statement
            Statement statement = connection.createStatement();

            // Execute a query
            String query = "SELECT * FROM mytable";
            ResultSet resultSet = statement.executeQuery(query);

            // Build web page
            out.println("<html><body>");
            out.println("<h1>Database Results</h1>");
            out.println("<table border='1'>");
            out.println("<tr><th>ID</th><th>Name</th><th>Age</th></tr>");

            while (resultSet.next()) {
                int id = resultSet.getInt("id");
                String name = resultSet.getString("name");
                int age = resultSet.getInt("age");
                out.println("<tr><td>" + id + "</td><td>" + name + "</td><td>" + age
                        + "</td></tr>");

                JSONObject dataObject = new JSONObject();
                dataObject.put("id", id);
                dataObject.put("name", name);
                dataObject.put("age", age);
                dataList.add(dataObject);

            }
            out.println("</table>");
        } catch (ClassNotFoundException e) {
            System.out.println("MySQL JDBC Driver not found.");
            e.printStackTrace();
        } catch (SQLException e) {
            System.out.println("Connection failed.");
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
            out.println("<h2>Error: " + e.getMessage() + "</h2>");
        } finally{
             dbSpan.end();
        }

        // Make a request to the Python microservice
        String averageAge = getAverageAge(dataList);
        out.println("<h2>Average Age: " + averageAge + "</h2>");
        out.println("</body></html>");

    }

    private String getAverageAge(List<JSONObject> dataList) throws IOException {

        Span computeSpan = tracer.spanBuilder(spanName:"ComputeRequest")
            .setSpanKind(spanKind.CLIENT)
            .setParent(parentContext)
            .startSpan();

        Context context = Context.current().with(computeSpan);
        
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost("http://ht-python-service:5000/compute_average_age");
            httpPost.setHeader("Content-Type", "application/json");

            JSONObject requestData = new JSONObject();
            requestData.put("data", new JSONArray(dataList));

            StringEntity entity = new StringEntity(requestData.toString());
            httpPost.setEntity(entity);

            // W3CTraceContext
            W3CTraceContextPropagator propagator = WwCTraceContextPropagator.getInstance();
            propagator.inject(context, httpPost, HttpPost::setHeader);
            
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseString = EntityUtils.toString(response.getEntity());
                JSONObject responseJson = new JSONObject(responseString);
                return responseJson.get("average_age").toString();
            }
        } finally {
            computeSpan.end();
        }
    }
}
