package smoke;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

// Duplicate application roots make augmentation reject this annotation even
// before tests run. A named entry point preserves the smoke app's default main.
@QuarkusMain(name = "indexing-regression")
public class CustomMain {
  public static void main(String... args) {
    Quarkus.run(args);
  }
}
