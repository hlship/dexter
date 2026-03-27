(ns net.lewisship.dex.main
  (:require [clojure.java.browse :as browse]
            [net.lewisship.dex.deps :as deps]
            [net.lewisship.dex.service :as service]))

(def ^:private default-port 10240)

(defn -main
  [& args]
  (let [path (or (first args) "test-resources/dex/project-deps.edn")]
    (println (str "Loading dependency data from: " path))
    (deps/load-db! path)
    (println (str "Loaded " (count (deps/artifact-keys @deps/*db)) " artifacts"))
    (service/start!)
    (let [url (str "http://localhost:" default-port)]
      (println (str "Server started at " url))
      (browse/browse-url url))))
