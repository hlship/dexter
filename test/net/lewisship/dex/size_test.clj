(ns net.lewisship.dex.size-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [net.lewisship.dex.size :as size]))

(deftest path-size-sums-files-in-directories
  (let [dir (fs/create-temp-dir {:prefix "dex-size-"})]
    (try
      (spit (str (fs/file dir "a.txt")) "12345")
      (fs/create-dirs (fs/file dir "sub"))
      (spit (str (fs/file dir "sub" "b.txt")) "123")
      (is (= 8 (size/path-size dir)))
      (is (= 5 (size/path-size (fs/file dir "a.txt"))))
      (is (= 0 (size/path-size (fs/file dir "missing"))))
      (is (= 8 (size/paths-size [dir (fs/file dir "missing")])))
      (is (nil? (size/paths-size [(fs/file dir "missing")])))
      (finally
        (fs/delete-tree dir)))))

(deftest format-size-handles-nil
  (is (nil? (size/format-size nil)))
  (is (string? (size/format-size 2048))))
