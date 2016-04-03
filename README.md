# timbre-logentries

Token-based Logentries appender for [Timbre] [1].

## Usage example

```clojure
(require '[timbre-logentries :refer [logentries-appender]])

(timbre/merge-config!
  {:appenders
   {:logentries (logentries-appender {:token "***"})}})

```

## License

Copyright © 2016 Johannes Staffans

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.


[1]: https://github.com/ptaoussanis/timbre
