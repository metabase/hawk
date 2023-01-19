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

## Leiningen-style test selection

You can run tests against a single namespace or directory, or one test specifically, by passing `:only [argument]`:

Arguments to `clojure -X` are read in as EDN; for things other than plain symbols or numbers you usually need to wrap
them in single quotes in your shell. Our test runner uses this argument to determine where to look for tests. Here's
how different EDN forms are interpreted as our test runner:

| Arg type | Example | Description |
| --- | --- | --- |
| Unqualified Symbol | `my.namespace-test` | Run all tests in this namespace |
| Qualified Symbol | `my.namespace-test/my-test` | Run one specific test |
| String | `'"test/metabase/api"'` | Run all tests in test namespaces in this directory (including subdirectories) |
| Vector of symbols/strings | `'[my.namespace "test/metabase/some_directory"]'` | Union of tests found by the individual items in the vector |

### Example commands:

| Description | Example |
| --- | --- |
| Run tests in a specific namespace | `clojure -X:test :only my.namespace-test` |
| Run a specific test | `clojure -X:test :only my.namespace-test/my-test` |
| Run tests in a specific directory (including subdirectories) | `clojure -X:test :only '"test/metabase/api"'` |
| Run tests in 2 namespaces | `clojure -X:test :only '[my.namespace-test my.other.namespace-test]'` |


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
Metabase test runner. All tests are ran synchronously unless they are given `^:parallel` metadata (either the test
itself, or the namespace).

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

## Run tests from the REPL

Run tests from the REPL the same way the CLI will run them:

```clj
(hawk.core/hawk.core/find-and-run-tests-repl {:only ['my.namespace-test]})
```

## Additional `is` assertion types

* `re=`: checks whether a string is equal to a regular expression
* `partial=`: like `=` but only compares stuff (using `clojure.data/diff`) that's in `expected`. Anything else is ignored.
* `=?`: see [Approximately Equal](/docs/approximately-equal.md)

## Test modes:

The Hawk test runner can run in one of three modes.

| Mode | Test Suite Failure Behavior | Show Progress Bar? |
|--|--|--|
| `:repl` | Print summary | No |
| `:cli/local` | call `(System/exit -1)` | Yes |
| `:cli/ci` | call `(System/exit -1)` | No |

The mode is determined as follows:

1. If an explicit `:mode` is passed to the options map (e.g. `:exec-args` or CLI args passed to `clojure -X`), it is
   used;

2. Otherwise, if the env var `HAWK_MODE` or Java system property `hawk.mode` is specified, it is used;

3. Otherwise, if the env var `CI` or system property `ci` is set, `:cli/ci` will be used;

4. If you use `hawk.core/find-and-run-tests-cli` as your `:exec-fn`, `:cli/local` will be used;

5. If you run tests from the REPL with `hawk.core/find-and-run-tests-repl`, `:repl` will be used.

## Matching Namespace Patterns

Tell the test runner to only run tests against certain namespaces with `:namespace-pattern`:

```clj
;; only run tests against namespaces that start with `my-project` and end with `test`
{:aliases
 {:test
  {:exec-fn            hawk.core/find-and-run-tests-cli
   :namespace-pattern "^my-project.*test$"}}}
```

## Excluding directories

`:exclude-directories` passed in the options map will tell Hawk not to look for tests in those directories. This only
works for directories on your classpath, i.e. things included in `:paths`! If you need something more sophisticated,
please submit a PR.

```clj
{:aliases
 {:test
  {:exec-fn             hawk.core/find-and-run-tests-cli
   :exclude-directories ["src" "resources" "shared/src"]}}}
```

## Additional options

All other options are passed directly to [Eftest](https://github.com/weavejester/eftest); refer to its documentation
for more information.

```
clj -X:test :fail-fast? true
```
