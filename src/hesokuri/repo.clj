; Copyright (C) 2013 Google Inc.
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;    http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns hesokuri.repo
  "An object that abstracts out the access to the git repository. This does not
  have logic that is specific to Hesokuri, so it can be easily replaced with a
  more performant git access layer later. Currently it just shells out to 'git'
  on the command line)."
  (:use [clojure.java.io :only [file]]
        [clojure.string :only [trim]]
        hesokuri.util
        hesokuri.watching))

(defn hex-char?
  "Returns true iff the given character is a hexadecimal character: 0-9 or a-f.
  Returns false for capitalized hex characters (A-F)."
  [c]
  (let [c (int c)]
    (or (and (>= c (int \a)) (<= c (int \f)))
        (and (>= c (int \0)) (<= c (int \9))))))

(defn full-hash?
  "Returns true iff the given string looks like a full, valid hash. It does not
  have to actually exist in any repo."
  [s]
  (and (= (count s) 40)
       (every? hex-char? s)))

(defn init
  "Returns a repo object that operates through the git command-line tool.
  Automatically initializes the repository if it does not exist."
  [dir]
  (let [init-result (*sh* "git" "init" (str dir))]
    (when (not= 0 (:exit init-result))
      (throw (java.io.IOException.
              (str "Failed to init repo: " init-result)))))
  {:dir (file dir)
   :bare (-> (file dir ".git") .isDirectory not)})

(defn- git-dir
  "Returns the git directory (.git) of the repo as a java.io.File object. If it
  is a bare repository, it is equal to the :dir value."
  [{:keys [dir bare] :as repo}]
  (if bare dir (file dir ".git")))

(defn working-area-clean
  "Returns true if this repo's working area is clean, or it is bare. It is clean
  if there are no untracked files, unstaged changes, or uncommitted changes."
  [{:keys [bare dir]}]
  (or bare
      (let [status (*sh* "git" "status" "--porcelain" :dir dir)]
        (and (= 0 (:exit status))
             (= "" (:out status))))))

(defn branches
  "Returns a map of refs/heads branches to their hashes."
  [repo]
  (into {}
   (let [heads-dir (file (git-dir repo) "refs" "heads")
         head-files (seq (.listFiles heads-dir))]
     (for [head-file head-files
           :let [hash (try (trim (slurp head-file))
                           (catch java.io.FileNotFoundException _ nil))]
           :when hash]
       [(.getName head-file) hash]))))

(defn checked-out?
  "Returns true if the given branch is checked out."
  [repo branch-name]
  {:pre [(string? branch-name)]}
  (= (trim (slurp (file (git-dir repo) "HEAD")))
     (str "ref: refs/heads/" branch-name)))

(defn delete-branch
  "Deletes the given branch. This method always returns nil. It does not throw
  an exception if the branch delete failed."
  [{:keys [dir]} branch-name]
  {:pre [(string? branch-name)]}
  (sh-print-when #(= (:exit %) 0) "git" "branch" "-d" branch-name :dir dir)
  nil)

(defn hard-reset
  "Performs a hard reset to the given ref. Returns 0 for success, non-zero for
  failure."
  [{:keys [dir]} ref]
  {:pre [(string? ref)]}
  (sh-print "git" "reset" "--hard" ref :dir dir))

(defn rename-branch
  "Renames the given branch, allowing overwrites if specified. Returns 0 for
  success, non-zero for failure."
  [{:keys [dir]} from to allow-overwrite]
  {:pre [(string? from) (string? to)]}
  (sh-print "git" "branch" (if allow-overwrite "-M" "-m") from to :dir dir))

(defn fast-forward?
  "Returns true iff the second hash is a fast-forward of the first hash. When
  the hashes are the same, returns when-equal."
  [{:keys [dir]} from-hash to-hash when-equal]
  {:pre [(full-hash? from-hash) (full-hash? to-hash)]}
  (if (= from-hash to-hash)
    when-equal
    (-> (*sh* "git" "merge-base" from-hash to-hash :dir dir)
        :out
        trim
        (= from-hash))))

(defn push-to-branch
  "Performs a push. Returns 0 for success, non-zero for failure."
  [{:keys [dir]} peer-repo local-ref remote-branch allow-non-ff]
  {:pre [(string? peer-repo) (string? local-ref) (string? remote-branch)]}
  (apply sh-print "git" "push" peer-repo
         (str local-ref ":refs/heads/" remote-branch)
         (concat (if allow-non-ff ["-f"] [])
                 [:dir dir])))

(defn watch-refs-heads-dir
  "Sets up a watcher for the refs/heads directory and returns an object like
  that returned by hesokuri.watching/watcher-for-dir. on-change is a function
  that takes no arguments and is called when a change is detected."
  [repo on-change]
  (watcher-for-dir (file (git-dir repo) "refs" "heads") (fn [_] (on-change))))
