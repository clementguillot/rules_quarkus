package smoke;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Reuses the build-time extension assertion against the already packaged application. */
@QuarkusIntegrationTest
class SmokeExtResourceIT extends SmokeExtResourceTest {}
