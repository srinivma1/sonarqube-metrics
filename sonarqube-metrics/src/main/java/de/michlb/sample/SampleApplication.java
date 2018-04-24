
package de.michlb.sample;

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.language.simple.SimpleLanguage;
import org.apache.camel.processor.aggregate.AggregationStrategy;
import org.apache.camel.util.InetAddressUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@SpringBootApplication


public class SampleApplication {



	private static int redeliveryCount = 0;

	public static void main(String[] args) {
		SpringApplication.run(SampleApplication.class, args);
	}
	
	
	
	
	@Bean
    public AggregationStrategy batchAggregationStrategy() {
            return new NumAggregationStrategy();
    }
	@Component
	class AuditRoute extends RouteBuilder {
		
		Predicate p = exchangeProperty("CamelSplitComplete").isEqualTo("true");
      
		@Override
		public void configure() {

			onException(RuntimeException.class).handled(true).useOriginalMessage().process(new Processor() {
				public void process(Exchange exchange) throws Exception {
					Exception exception = (Exception) exchange.getProperty(Exchange.EXCEPTION_CAUGHT);

					exchange.getIn().setHeader("exceptionClass", exception.getClass().getSimpleName());
					exchange.getIn().setHeader("failedRouteId",
							(String) exchange.getProperty(Exchange.FAILURE_ROUTE_ID));
					exchange.getIn().setHeader("failedEndpointUri",
							(String) exchange.getProperty(Exchange.FAILURE_ENDPOINT));
					exchange.getIn().setHeader("originalEndpoint", exchange.getFromEndpoint().getEndpointUri());
					exchange.getIn().setHeader("machineName", InetAddressUtil.getLocalHostName());
					exchange.getIn().setHeader("originalPayload",
							(String) exchange.getUnitOfWork().getOriginalInMessage().getBody());
					exchange.getIn().setHeader("redeliveryCount", redeliveryCount);
					Expression exp = SimpleLanguage.simple("${date:now:dd-MM-yyyy}");
					Object dateString = exp.evaluate(exchange, Object.class);

					exchange.getIn().setHeader("currentTimestamp", dateString);
					Expression exceptionExp = SimpleLanguage.simple("${exception.stacktrace}");
					String exceptionString = exceptionExp.evaluate(exchange, String.class);
					exchange.getIn().setHeader("exceptionMessage", exceptionString);
					exchange.getIn().setBody((String) exchange.getUnitOfWork().getOriginalInMessage().getBody());

					System.out.println(exchange.getIn().getHeader("currentTimestamp"));

					// log, email, reroute, etc.
				}
			}).log("${body}");

			// TODO:Check if we are getting header from Azure Service Bus
			
			
			
			from("timer://sonar?repeatCount=1").routeId("directSonar").streamCaching()
			.removeHeaders("*")
			.inOnly("http://localhost:9000/sonarqube/api/projects/index")
			.log("${body}")
		
				.process(new Processor() {
						@Override
						public void process(Exchange exchange) throws Exception {
							
							List<String> componentIdList = new ArrayList<String>();
							
							String jsonBody = exchange.getIn().getBody(String.class);
							
							JSONArray jsonArr = new JSONArray(jsonBody);

					        for (int i = 0; i < jsonArr.length(); i++)
					        {
					            JSONObject jsonObj = jsonArr.getJSONObject(i);

					            componentIdList.add((String)jsonObj.get("k"));
					        }
					        
					        exchange.getIn().setBody(componentIdList);

						}
					}).split()
					.body().shareUnitOfWork()
					.setHeader(Exchange.HTTP_QUERY, simple("componentKey=${body}&metricKeys=ncloc"))
					. to("http://localhost:9000/sonarqube/api/measures/component")
					.log("${body}")
					.log("${exchangeProperty.CamelSplitComplete}")
					
					.process(new Processor() {
						@Override
						public void process(Exchange exchange) throws Exception {
							
							
							String jsonBody = exchange.getIn().getBody(String.class);
							JSONObject jsonObj = new JSONObject(jsonBody);
							JSONObject componentObj = (JSONObject)jsonObj.get("component");
							JSONArray jsonArray = componentObj.getJSONArray("measures");
							JSONObject arrayObj = jsonArray.getJSONObject(0);
							 int ncLoc = arrayObj.getInt("value");      
					        
					        exchange.getIn().setBody(ncLoc);

						}
					})
					.aggregate(batchAggregationStrategy()).constant(true).completionTimeout(3).log("Global Loc is ${body}").end();
				   
					
			// ;
		}
	}
}
