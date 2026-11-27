(ns net.lewisship.dex.size
  "Helpers for measuring the on-disk size of classpath entries (JAR files and
  directories), and for formatting sizes for display."
  (:require [babashka.fs :as fs]
            [clj-commons.humanize :as humanize])
  (:import (java.io File)))

(defn path-size
  "Total size in bytes of a classpath entry: the length of a file (such as a
  JAR), or the sum of the lengths of all regular files under a directory.
  Returns 0 when the path does not exist."
  [path]
  (let [^File f (fs/file path)]
    (cond
      (.isFile f) (.length f)
      (.isDirectory f) (->> (file-seq f)
                            (filter #(.isFile ^File %))
                            (map #(.length ^File %))
                            (reduce + 0))
      :else 0)))

(defn paths-size
  "Total size in bytes of a collection of classpath entries, or nil if none exist."
  [paths]
  (let [existing (filter #(fs/exists? %) paths)]
    (when (seq existing)
      (transduce (map path-size) + 0 existing))))

(defn format-size
  "Formats a size in bytes for display (e.g., \"1.2 MB\"), or nil for a nil size."
  [bytes]
  (when bytes
    (humanize/filesize bytes)))
