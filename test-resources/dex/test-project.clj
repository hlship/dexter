{[cheshire "5.13.0"]
 {[com.fasterxml.jackson.dataformat/jackson-dataformat-cbor
   "2.17.0"
   :exclusions
   [[com.fasterxml.jackson.core/jackson-databind]]]
  nil,
  [com.fasterxml.jackson.dataformat/jackson-dataformat-smile
   "2.17.0"
   :exclusions
   [[com.fasterxml.jackson.core/jackson-databind]]]
  nil,
  [tigris "0.1.2"] nil},
 [cljdev "0.16.0"]
 {[io.github.tonsky/clj-reload "0.9.0"] nil,
  [mvxcvi/puget "1.3.4"] {[mvxcvi/arrangement "2.1.0"] nil}},
 [codestyle "0.54.0" :scope "test"] nil,
 [codox-theme-rdash "0.1.2" :scope "test"] nil,
 [colorize "0.1.1" :exclusions [[org.clojure/clojure]]] nil,
 [com.amazonaws/aws-java-sdk-s3 "1.12.791"]
 {[com.amazonaws/aws-java-sdk-core "1.12.791"]
  {[com.fasterxml.jackson.core/jackson-databind "2.17.2"]
   {[com.fasterxml.jackson.core/jackson-annotations "2.17.2"] nil},
   [commons-logging "1.1.3"] nil,
   [joda-time "2.12.7"] nil,
   [org.apache.httpcomponents/httpclient "4.5.13"]
   {[org.apache.httpcomponents/httpcore "4.4.13"] nil}},
  [com.amazonaws/aws-java-sdk-kms "1.12.791"] nil,
  [com.amazonaws/jmespath-java "1.12.791"] nil},
 [com.cognitect.aws/api "0.8.774"]
 {[org.clojure/data.json "2.5.1"] nil,
  [org.clojure/tools.logging "1.3.0"] nil},
 [com.cognitect.aws/endpoints "871.2.41.6"] nil,
 [com.cognitect.aws/s3 "871.2.40.9"] nil,
 [com.cognitect/transit-clj "1.0.333"]
 {[com.cognitect/transit-java "1.0.371"]
  {[javax.xml.bind/jaxb-api "2.4.0-b180830.0359"]
   {[javax.activation/javax.activation-api "1.2.0"] nil},
   [org.msgpack/msgpack "0.6.12"]
   {[com.googlecode.json-simple/json-simple
     "1.1.1"
     :exclusions
     [[junit]]]
    nil,
    [org.javassist/javassist "3.18.1-GA"] nil}}},
 [com.fasterxml.jackson.core/jackson-core "2.20.0"] nil,
 [com.stuartsierra/component "1.2.0"]
 {[com.stuartsierra/dependency "1.0.0"] nil},
 [commons-codec "1.20.0"] nil,
 [criterium "0.4.6" :scope "test"] nil,
 [dev.ericdallo/metrepl "0.5.2"]
 {[io.opentelemetry/opentelemetry-api "1.51.0"]
  {[io.opentelemetry/opentelemetry-context "1.51.0"] nil},
  [io.opentelemetry/opentelemetry-exporter-otlp "1.51.0"]
  {[io.opentelemetry/opentelemetry-exporter-otlp-common
    "1.51.0"
    :scope
    "runtime"]
   {[io.opentelemetry/opentelemetry-exporter-common
     "1.51.0"
     :scope
     "runtime"]
    nil},
   [io.opentelemetry/opentelemetry-exporter-sender-okhttp
    "1.51.0"
    :scope
    "runtime"]
   {[com.squareup.okhttp3/okhttp "4.12.0" :scope "runtime"]
    {[com.squareup.okio/okio "3.6.0" :scope "runtime"]
     {[com.squareup.okio/okio-jvm "3.6.0" :scope "runtime"]
      {[org.jetbrains.kotlin/kotlin-stdlib-common
        "1.9.10"
        :scope
        "runtime"]
       nil}},
     [org.jetbrains.kotlin/kotlin-stdlib-jdk8
      "1.8.21"
      :scope
      "runtime"]
     {[org.jetbrains.kotlin/kotlin-stdlib-jdk7
       "1.8.21"
       :scope
       "runtime"]
      nil,
      [org.jetbrains.kotlin/kotlin-stdlib "1.8.21" :scope "runtime"]
      {[org.jetbrains/annotations "13.0" :scope "runtime"] nil}}}},
   [io.opentelemetry/opentelemetry-sdk-logs "1.51.0"] nil,
   [io.opentelemetry/opentelemetry-sdk-metrics "1.51.0"] nil,
   [io.opentelemetry/opentelemetry-sdk-trace "1.51.0"] nil},
  [io.opentelemetry/opentelemetry-sdk-extension-autoconfigure "1.51.0"]
  {[io.opentelemetry/opentelemetry-sdk-extension-autoconfigure-spi
    "1.51.0"]
   nil,
   [io.opentelemetry/opentelemetry-sdk "1.51.0"]
   {[io.opentelemetry/opentelemetry-sdk-common "1.51.0"] nil}},
  [org.clojure/java.classpath "1.1.0"] nil},
 [dev.nu/nu-logging "0.6.0"] nil,
 [meta-merge "1.0.0"] nil,
 [midje "1.10.10" :scope "provided"]
 {[clj-time
   "0.15.2"
   :scope
   "provided"
   :exclusions
   [[org.clojure/clojure]]]
  nil,
  [flare "0.2.9" :scope "provided" :exclusions [[org.clojure/clojure]]]
  {[org.clojars.brenton/google-diff-match-patch
    "0.1"
    :scope
    "provided"]
   nil},
  [io.aviso/pretty
   "1.4.4"
   :scope
   "provided"
   :exclusions
   [[org.clojure/clojure]]]
  nil,
  [marick/suchwow
   "6.0.3"
   :scope
   "provided"
   :exclusions
   [[org.clojure/clojure] [org.clojure/clojurescript]]]
  {[com.rpl/specter
    "1.1.3"
    :scope
    "provided"
    :exclusions
    [[org.clojure/clojure] [org.clojure/clojurescript]]]
   nil,
   [environ
    "1.2.0"
    :scope
    "provided"
    :exclusions
    [[org.clojure/clojure]]]
   nil,
   [potemkin
    "0.4.5"
    :scope
    "provided"
    :exclusions
    [[org.clojure/clojure]]]
   {[clj-tuple "0.2.2" :scope "provided"] nil,
    [riddley "0.1.12" :scope "provided"] nil}},
  [org.clojure/core.unify
   "0.5.7"
   :scope
   "provided"
   :exclusions
   [[org.clojure/clojure]]]
  nil,
  [org.clojure/math.combinatorics "0.2.0" :scope "provided"] nil,
  [org.clojure/tools.macro "0.1.5" :scope "provided"] nil,
  [org.clojure/tools.namespace "1.4.4" :scope "provided"] nil,
  [org.tcrawley/dynapath "1.1.0" :scope "provided"] nil},
 [nrepl "1.0.0" :exclusions [[org.clojure/clojure]]] nil,
 [nu/nu-logback-encoder "1.7.0"]
 {[ch.qos.logback/logback-classic "1.3.16"]
  {[ch.qos.logback/logback-core "1.3.16"] nil},
  [com.amazonaws/aws-lambda-java-core "1.2.2"] nil,
  [metosin/jsonista "0.3.7"]
  {[com.fasterxml.jackson.datatype/jackson-datatype-jsr310 "2.14.1"]
   nil}},
 [nubank/matcher-combinators "3.9.2" :scope "test"] nil,
 [nubank/mockfn "0.7.0" :scope "test"] nil,
 [org.apache.commons/commons-math3 "3.6.1"] nil,
 [org.clojure/clojure "1.12.3"]
 {[org.clojure/core.specs.alpha "0.4.74"] nil,
  [org.clojure/spec.alpha "0.5.238"] nil},
 [org.clojure/core.async "1.8.741"]
 {[org.clojure/tools.analyzer.jvm "1.3.2"]
  {[org.clojure/tools.analyzer "1.2.0"] nil,
   [org.ow2.asm/asm "9.2"] nil}},
 [org.clojure/core.memoize "1.1.266"]
 {[org.clojure/core.cache "1.1.234"]
  {[org.clojure/data.priority-map "1.2.0"] nil}},
 [org.clojure/data.xml "0.2.0-alpha9" :scope "test"] nil,
 [org.clojure/test.check "1.1.2" :scope "test"] nil,
 [org.nrepl/incomplete "0.1.0" :exclusions [[org.clojure/clojure]]]
 nil,
 [org.slf4j/log4j-over-slf4j "2.0.17"] nil,
 [org.slf4j/slf4j-api "2.0.17"] nil,
 [prismatic/plumbing "0.6.0"]
 {[de.kotka/lazymap "3.1.0" :exclusions [[org.clojure/clojure]]] nil},
 [prismatic/schema "1.4.1"] nil,
 [selmer "1.12.69"] nil,
 [thheller/shadow-cljs "3.3.4" :scope "test"]
 {[cider/piggieback
   "0.6.0"
   :scope
   "test"
   :exclusions
   [[org.clojure/clojure] [org.clojure/clojurescript] [nrepl]]]
  nil,
  [com.bhauman/cljs-test-display "0.1.1" :scope "test"] nil,
  [com.cognitect/transit-cljs "0.8.280" :scope "test"]
  {[com.cognitect/transit-js "0.8.874" :scope "test"] nil},
  [com.google.javascript/closure-compiler "v20250407" :scope "test"]
  nil,
  [expound "0.9.0" :scope "test"] nil,
  [fipp "0.6.27"] {[org.clojure/core.rrb-vector "0.1.2"] nil},
  [hiccup "1.0.5" :scope "test"] nil,
  [io.methvin/directory-watcher "0.19.0" :scope "test"]
  {[net.java.dev.jna/jna "5.16.0" :scope "test"] nil},
  [org.clojure/clojurescript
   "1.12.134"
   :scope
   "test"
   :exclusions
   [[com.google.javascript/closure-compiler]
    [org.clojure/google-closure-library]
    [org.clojure/google-closure-library-third-party]]]
  nil,
  [org.clojure/google-closure-library-third-party
   "0.0-20250515-f04e4c0e"
   :scope
   "test"]
  nil,
  [org.clojure/google-closure-library
   "0.0-20250515-f04e4c0e"
   :scope
   "test"]
  nil,
  [org.clojure/tools.cli "1.1.230" :scope "test"] nil,
  [org.clojure/tools.reader "1.5.2"] nil,
  [ring/ring-core "1.14.1" :scope "test" :exclusions [[clj-time]]]
  {[commons-io "2.18.0" :scope "test"] nil,
   [crypto-equality "1.0.1" :scope "test"] nil,
   [crypto-random "1.2.1" :scope "test"] nil,
   [org.apache.commons/commons-fileupload2-core
    "2.0.0-M2"
    :scope
    "test"]
   nil,
   [org.ring-clojure/ring-core-protocols "1.14.1" :scope "test"] nil,
   [org.ring-clojure/ring-websocket-protocols "1.14.1" :scope "test"]
   nil,
   [ring/ring-codec "1.3.0" :scope "test"] nil},
  [thheller/shadow-client "1.4.0" :scope "test"] nil,
  [thheller/shadow-cljsjs "0.0.22" :scope "test"] nil,
  [thheller/shadow-undertow "0.3.4" :scope "test"]
  {[io.undertow/undertow-core "2.3.10.Final" :scope "test"]
   {[org.jboss.logging/jboss-logging "3.4.3.Final" :scope "test"] nil,
    [org.jboss.threads/jboss-threads
     "3.5.0.Final"
     :scope
     "test"
     :exclusions
     [[org.wildfly.common/wildfly-common]]]
    nil,
    [org.jboss.xnio/xnio-api
     "3.8.8.Final"
     :scope
     "test"
     :exclusions
     [[org.jboss.threads/jboss-threads]]]
    {[org.wildfly.client/wildfly-client-config
      "1.0.1.Final"
      :scope
      "test"
      :exclusions
      [[org.wildfly.common/wildfly-common]]]
     nil,
     [org.wildfly.common/wildfly-common "1.5.4.Final" :scope "test"]
     nil},
    [org.jboss.xnio/xnio-nio
     "3.8.8.Final"
     :scope
     "test"
     :exclusions
     [[org.wildfly.common/wildfly-common]]]
    nil}},
  [thheller/shadow-util "0.7.0" :scope "test"] nil}}
