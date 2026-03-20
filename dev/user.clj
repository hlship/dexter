(ns user
  (:require [net.lewisship.trace :as trace]
            clj-reload.core
            [clj-commons.pretty.repl :as repl]))

(repl/install-pretty-exceptions)
(trace/setup-default)

(trace/trace :startup true)

(comment
  (trace/set-enable-trace! false)
  (trace/set-enable-trace! true)

  )
