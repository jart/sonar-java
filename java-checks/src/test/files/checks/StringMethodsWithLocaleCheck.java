import static java.lang.String.format;
class A {
  void foo() {
    String myString ="";
    myString.toLowerCase(); // Noncompliant [[sc=14;ec=25]]
    myString.toUpperCase(); // Noncompliant
    myString.toLowerCase(java.util.Locale.US);
    myString.toUpperCase(java.util.Locale.US);

    myString.format("foo"); // Noncompliant [[sc=14;ec=20]]
    myString.format("foo", "bar", "qix"); // Noncompliant
    myString.format(java.util.Locale.US, "foo", "bar", "qix");
    format("foo"); // Noncompliant [[sc=5;ec=11]] {{Define the locale to be used in this String operation.}}

  }
}
