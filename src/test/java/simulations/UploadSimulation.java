package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class UploadSimulation extends Simulation {

    // 1. HTTP protocol config (base URL, headers par défaut)
    HttpProtocolBuilder httpProtocol = http
            .baseUrl("http://localhost:8080")
            .contentTypeHeader("application/octet-stream");

    // 2. Scenario — ce que fait chaque client virtuel
    ScenarioBuilder scn = scenario("Upload zip")
            .exec(http("upload")
                    .post("/uploads")
                    .body(RawFileBody("data/test-500.zip"))
            );

    // 3. Setup — charge et assertions
    {
        setUp(
                scn.injectOpen(atOnceUsers(1000))
        ).protocols(httpProtocol);
    }
}