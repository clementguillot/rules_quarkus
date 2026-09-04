package fixture;

public final class Main {
  public static String message() {
    return Value.message() + "/" + smoke.generated.GeneratedMain.message();
  }
}
