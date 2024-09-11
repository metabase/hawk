[![Clojars Project](https://clojars.org/io.github.metabase/hawk/latest-version.svg)](https://clojars.org/io.github.metabase/hawk/)

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
   :exec-fn     mb.hawk.core/find-and-run-tests-cli}}}
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

You can use `mb.hawk.init/assert-tests-are-not-initializing` to make sure things that shouldn't be happening as a
side-effect of loading namespaces, such as initializing a database, are not happening where they shouldn't be.

```clj
(ns my.namespace
  (:require
   [mb.hawk.init]))

(defn initialize-database! []
  (mb.hawk.init/assert-tests-are-not-initializing "Don't initialize the database in a top-level form!")
  ...)
```

## Fancy JUnit Output

Hawk automatically generates JUnit output using bespoke JUnit output code that prints diffs using
[humane-test-output](https://github.com/pjstadig/humane-test-output). JUnit output is automatically output to
`target/junit`, and only in `:cli/ci` mode. Not currently configurable! Submit a PR if you want to output it somewhere
else.

## Parallel Tests

Unlike Eftest, parallelization in Hawk tests is opt-in. This is mostly a byproduct of it beginning life as the
Metabase test runner. All tests are ran synchronously unless they are given `^:parallel` metadata (either the test
itself, or the namespace).

Hawk includes `mb.hawk.parallel/assert-test-is-not-parallel`, which you can use to make sure things that shouldn't be ran
in parallel tests are not:

```clj
(ns my.namespace
  (require [mb.hawk.parallel]))

(defn do-with-something-redefined [thunk]
  (mb.hawk.parallel/assert-test-is-not-parallel "Don't use do-with-something-redefined inside parallel tests!")
  (with-redefs [something something-else]
    (thunk)))
```

## Run tests from the REPL

Run tests from the REPL the same way the CLI will run them:

```clj
(mb.hawk.core/find-and-run-tests-repl {:only ['my.namespace-test]})
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

4. If you use `mb.hawk.core/find-and-run-tests-cli` as your `:exec-fn`, `:cli/local` will be used;

5. If you run tests from the REPL with `mb.hawk.core/find-and-run-tests-repl`, `:repl` will be used.

## Matching Namespace Patterns

Tell the test runner to only run tests against certain namespaces with `:namespace-pattern`:

```clj
;; only run tests against namespaces that start with `my-project` and end with `test`
{:aliases
 {:test
  {:exec-fn    mb.hawk.core/find-and-run-tests-cli
   :exec-args {:namespace-pattern "^my-project.*test$"}}}}
```

## Excluding directories

`:exclude-directories` passed in the options map will tell Hawk not to look for tests in those directories. This only
works for directories on your classpath, i.e. things included in `:paths`! If you need something more sophisticated,
please submit a PR.

```clj
{:aliases
 {:test
  {:exec-fn   mb.hawk.core/find-and-run-tests-cli
   :exec-args {:exclude-directories ["src" "resources" "shared/src"]}}}}
```

## Skipping namespaces or vars with tags

You can optionally exclude tests in namespaces with certain tags by specifying the `:exclude-tags` option:

```clj
{:aliases
 {:test
  {:exec-fn   mb.hawk.core/find-and-run-tests-cli
   :exec-args {:exclude-tags [:my-project/skip-namespace]}}}}
```

or

```
clj -X:test :exclude-tags '[:my-project/skip-namespace]'
```

And adding it to namespaces like

```clj
(ns ^:my-project/skip-namespace my.namespace
  ...)
```

## Only running tests against namespaces or vars with tags

The opposite of `:exclude-tags` -- you can only run tests against a certain set of tags with `:only-tags`. If multiple
`:only-tags` are specified, only namespaces or vars that have all of those tags will be run.

`:only-tags` can be combined with `:only` and/or `:exclude-tags`.

```
clj -X:test :only-tags [:my-project/e2e-test]
```

will only run tests in namespaces like

```clj
(ns ^:my-project/e2e-test my-namespace
  ...)
```

or ones individually marked `:my-project/e2e-test` like

```clj
(deftest ^:my-project/e2e-test my-test
  ...)
```

## Whole-Suite Hooks

You can specify hooks to run before or after the entire test suite runs like so:

```clj
(methodical/defmethod mb.hawk.hooks/before-run ::my-hook
  [_options]
  (do-something-before-test-suite-starts!))

(methodical/defmethod mb.hawk.hooks/after-run ::my-hook
  [_options]
  (do-cleanup-when-test-suite-finishes!))
```

`options` are the same options passed to the test runner as a whole, i.e. a combination of those specified in your
`deps.edn` aliases as well as additional command-line options.

The dispatch value is not particularly important -- one hook will run for each dispatch value -- but you should probably
make it a namespaced keyword to avoid conflicts, and give it a docstring so people know why it's there. The order the
hooks are run in is indeterminate. The docstrings for `before-run` and `after-run` are updated automatically as new
hooks are added; you can check it to see which hooks are in use. Note that hooks will not be ran unless the namespace
they live in is loaded; this may be affected by `:only` options passed to the test runner.

Return values of methods are ignored; they are done purely for side effects.

## Partitioning tests

You can divide a test suite into multiple partitions using the `:partition/total` and `:partition/index` keys. This is
an easy way to speed up CI by diving large test suites into multiple jobs.

```
clj -X:test '{:partition/total 10, :partition/index 8}'
...
Running tests in partition 9 of 10 (575 tests of 5753)...
Finding tests took 46.6 s.
Running 575 tests
...
```

`:partition/index` is zero-based, e.g. if you have ten partitions (`:partiton/total 10`) then the first partition is `0`
and the last is `9`.

Tests are partitioned at the var (`deftest`) level after all tests are found the usual way, but all tests in any given
namespace will always be split into the same partition. All namespaces that would be loaded if you were running the
entire test suite are still loaded. Partitions are split as evenly as possible, but tests are guaranteed to be split
deterministically into exactly the number of partitions you asked for.


## Additional options

All other options are passed directly to [Eftest](https://github.com/weavejester/eftest); refer to its documentation
for more information.

```
clj -X:test '{:fail-fast? true}'
```
