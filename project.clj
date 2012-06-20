(defproject clj-jetty-proxy "1.0.9"
  :description "FIXME: write description"
  :dependencies
  [[org.clojure/clojure "1.3.0"]
   [org.eclipse.jetty/jetty-servlet "8.1.4.v20120524"]
   [org.eclipse.jetty/jetty-servlets "8.1.4.v20120524"]
   [clj-zoo-service-tracker "1.0.8"]
   [log4j/log4j "1.2.16"]
   [org.clojure/tools.logging "0.2.3"]
   [org.clojure/data.xml "0.0.4"]
   [clj-statsd "0.3.3"]]
  :repl-init clj-jetty-proxy.proxyservlet
  :main clj-jetty-proxy.proxyservlet
  :uberjar-exclusions [#"(?i)^META-INF/[^/]*\.SF$"]
  :aot :all
  :warn-on-reflection true
  :jar-exclusions [#"project.clj"]
  :omit-source true
  :plugins [[jonase/kibit "0.0.4"]])
