package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class UploadSimulation extends Simulation {

    // Target URL — overridable via -Dziprace.target.url=http://app:8080
    // Default points to localhost for native runs.
    private static final String TARGET_URL =
            System.getProperty("ziprace.target.url", "http://localhost:8080");

    // Number of concurrent users — overridable via -Dziprace.users=5000
    private static final int USERS = Integer.parseInt(
            System.getProperty("ziprace.users", "1000"));

    // 1. HTTP protocol config
    HttpProtocolBuilder httpProtocol = http
            .baseUrl(TARGET_URL)
            .contentTypeHeader("application/octet-stream");

    // 2. Scenario
    ScenarioBuilder scn = scenario("Upload zip")
            .exec(http("upload")
                    .post("/uploads")
                    .body(RawFileBody("data/test-500.zip"))
            );

    // 3. Setup
    {
        setUp(
                scn.injectOpen(atOnceUsers(USERS))
        ).protocols(httpProtocol);
    }
}