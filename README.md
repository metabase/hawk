# Hawk

It watches your code like a hawk! You like tests, right? Then run them with our state-of-the-art Clojure test runner.

![Test Hawk](https://github.com/metabase/hawk/raw/main/assets/test_hawk.png)

```clj
;;; this is not necessarily up to date; use the latest SHA in GitHub
{io.github.metabase/hawk {:sha "ca1775da999ed066947bd37ca5710167f4adecaa"}}
```

Hawk is a Clojure-CLI friendly wrapper around [Eftest](https://github.com/weavejester/eftest) with some extra features
and opinionated behavior. It started out as the [Metabase](https://github.com/metabase/metabase) test runner, but we
spun it out so we can use it in other places too.

## Example `deps.edn` config

```clj
{:aliases
 {:test
  {:extra-paths ["test"]
   :extra-deps  {io.github.metabase/hawk {:sha "ca1775da999ed066947bd37ca5710167f4adecaa"}}
   :exec-fn     hawk.core/find-and-run-tests-cli}}}
```

## Checking to make sure things don't happen during initialization

You can use `hawk.init/assert-tests-are-not-initializing` to make sure things that shouldn't be happening as a
side-effect of loading namespaces, such as initializing a database, are not happening where they shouldn't be.

```clj
(ns my.namespace
  (:require
   [hawk.init]))

(defn initialize-database! []
  (hawk.init/assert-tests-are-not-initializing "Don't initialize the database in a top-level form!")
  ...)
```

## Fancy JUnit Output

Hawk automatically generates JUnit output using bespoke JUnit output code that prints diffs using
[humane-test-output](https://github.com/pjstadig/humane-test-output). JUnit output is automatically output to
`target/junit`. Not currently configurable! Submit a PR if you want to output it somewhere else.

## Parallel Tests

Unlike Eftest, parallelization in Hawk tests is opt-in. This is mostly a byproduct of it beginning life as the
Metabase test runner. All tests are ran synchronously unless they are given `^:parallel` metadata.

Hawk includes `hawk.parallel/assert-test-is-not-parallel`, which you can use to make sure things that shouldn't be ran
in parallel tests are not:

```clj
(ns my.namespace
  (require [hawk.parallel]))

(defn do-with-something-redefined [thunk]
  (hawk.parallel/assert-test-is-not-parallel "Don't use do-with-something-redefined inside parallel tests!")
  (with-redefs [something something-else]
    (thunk)))
```

## Test modes:

The Hawk test runner can one in one of three modes.

* `:repl`      -- running locally in a REPL
* `:cli/ci`    -- running in a CI environment like CircleCI or GitHub actions with `clojure` or `clj`
* `:cli/local` -- running *locally* with `clojure` or `clj`

Which mode determines different behaviors, e.g. when running from a `:repl` we should not call `System/exit` when
tests fail; when running from `:cli/local` we should print use the pretty progress-bar reporter, etc.

You can specify the mode with env var `HAWK_MODE` or Java system property `hawk.mode`, or pass in `:mode` to the
options map in `deps.edn`. If the env var `CI` or system property `ci` is set, `:cli/ci` will be assumed, but
`HAWK_MODE` will be used preferentially.

If you use the `hawk.core/find-and-run-tests-cli` `:exec-fn`, `:cli/local` will be assumed if not otherwise specified
(e.g. if `CI` and `HAWK_MODE` are unset). When running tests from a REPL, use `hawk.core/find-and-run-tests-repl`
instead.

## Additional `is` assertion types

* `re=`: checks whether a string is equal to a regular expression
* `partial=`: like `=` but only compares stuff (using `clojure.data/diff`) that's in `expected`. Anything else is ignored.
* `=?`: see [Approximately Equal](/docs/approximately-equal.md)
