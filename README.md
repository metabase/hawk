# Hawk

It watches your code like a hawk! You like tests, right? Then run them with our state-of-the-art Clojure test runner.

![Test Hawk](https://github.com/metabase/hawk/raw/main/assets/test_hawk.png)

## Custom Assertion Types

* `re=`
* `partial=`: like `=` but only compares stuff (using `clojure.data/diff`) that's in `expected`. Anything else is ignored.
* `=?`: see [Approximately Equal](/docs/approximately-equal.md)
