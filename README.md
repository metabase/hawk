# test-runner
The Metabase meta-test-runner. You like tests, right? Then run them with our state-of-the-art Clojure test runner.

## Custom Assertion Types

* `re=`
* `partial=`: like `=` but only compares stuff (using [[data/diff]]) that's in `expected`. Anything else is ignored.
* `=?`: see (/docs/approximately-equals.md)
