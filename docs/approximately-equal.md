# `=?`

This adds a new test expression type `=?` that uses a [Methodical](https://github.com/camsaul/methodical) multimethod
to decide whether `expected` and `actual` should be "approximately equal". It dispatches on the types of `expected`
and `actual`.

Now while you can already write all the sort of "approximately equal" things you want in theory using `schema=`
(defined in [[hawk.assert-exprs]]), in practice it's a bit of a hassle. Want to convert an `=` to
`schema=` and change one key in a map to use `s/Int` instead of a specific number? Have fun wrapping every other value
in `s/eq`. Want to ignore unused keys like `partial=`? You need to stick `s/Keyword s/Any` in every. single. map. `=?`
takes the best of `schema=` and `partial=`, steals a few ideas from
[Expectations](https://github.com/clojure-expectations/expectations), and is more powerful and easier to use than any
of those three.

`=` usages can be replaced with `=?` with no other changes -- you can replace that one single key with a predicate
function and leave everything else the same.

Here's some rules I've defined already:

- Two regex patterns that are the exact same pattern should be considered =?. (For some wacko reason regex patterns
  aren't equal unless they're the same object)

- An `expected` plain Clojure map should be approximately equal to an `actual` record type. We shouldn't need some
  hack like `mt/derecordize` to be able to write tests for this stuff

- an `expected` regex pattern should be approximately equal to an `actual` string if the string matches the
  regex. (This is what `re=` currently does. We can replace `re=` with `=?` entirely.)

- an `expected` function should be approximately equal to a an `actual` value if `(expected actual)` returns truthy.

- an `expected` map should be approximately equal to an `actual` map if all the keys in `expected` are present in
  `actual` and their respective values are approximately equal. In other words, extra keys in `actual` should be
  ignored (this is what our `partial=` works)

- Motivating example: two sublcasses of `Temporal` e.g. `OffsetDateTime` and `ZonedDateTime` should be `=?` if we
  would print them exactly the same way.

Defining new `=?` behaviors is as simple as writing a new `defmethod`.

```clj
(methodical/defmethod =?-diff [java.util.regex.Pattern String]
  [expected-regex s]
  (when-not (re-matches expected-regex s)
    (list 'not (list 're-matches expected-regex s))))
```

Methods are expected to return `nil` if things are approximately equal, or a form explaining why they aren't if they
aren't. In this case, it returns something like

```clj
(not (re-matches #"\d+cans" "toucans")))
```

This is printed in the correct place by humanized test output and other things that can print diffs.

## Reader tags:

### `#hawk/exactly`

`#hawk/exactly` means results have to be exactly equal as if by `=`. Use this to get around the normal way `=?` would
compare things. This works inside collections as well.

```clj
(is (=? {:m #hawk/exactly {:a 1}}
        {:m {:a 1, :b 2}}))
;; =>
expected: {:m #hawk/exactly {:a 1}}

  actual: {:m {:a 1, :b 2}}
    diff: - {:m (not (= #hawk/exactly {:a 1} {:a 1, :b 2}))}
          + nil
```

### `#hawk/schema`

`#hawk/schema` compares things to a `schema.core` Schema:

```clj
(is (=? {:a 1, :b #hawk/schema {s/Keyword s/Int}}
        {:a 1, :b {:c 2}}))
=> ok

(is (=? {:a 1, :b #hawk/schema {s/Keyword s/Int}}
        {:a 1, :b {:c 2.0}}))
=>
expected: {:a 1, :b #hawk/schema {(pred keyword?) (pred integer?)}}

  actual: {:a 1, :b {:c 2.0}}
    diff: - {:b {:c (not (integer? 2.0))}}
          + nil
```

### `#hawk/approx`

`#hawk/approx` compares whether two numbers are approximately equal:

```clj
;; is the difference between actual and 1.5 less than ±0.1?
(is (=? #hawk/approx [1.5 0.1]
        1.51))
=> true

(is (=? #hawk/approx [1.5 0.1]
        1.6))
=>
expected: #hawk/approx [1.5 0.1]

  actual: 1.6
    diff: - (not (approx= 1.5 1.6 #_epsilon 0.1))
          + nil
```
