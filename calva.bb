;; fetch latest published Calva package.json
;; https://github.com/BetterThanTomorrow/calva/blob/published/package.json
;; parse and look for contributes -> configuration -> properties
;; for each property, look for default
;; later, we can drill into sub-properties

;; fetch the project settings.json if any $CWD/.vscode/settings.json

;; fetch the user settings.json if any
;; this might need to be provided as a directory since WSL on Windows
;; needs to reach out to /mnt/c/Users/<username>/AppData/Roaming/Code/User/settings.json

;; in project/user settings, look for calva.* settings

(ns calva
	(:require
	 [cheshire.core :as json]
	 [clojure.java.io :as io]
	 [clojure.string :as str]))

(def calva-package-json-url
	"https://raw.githubusercontent.com/BetterThanTomorrow/calva/published/package.json")

(defn- key-name [k]
	(if (keyword? k) (name k) (str k)))

(defn- parse-args
	"Parses --key value style args into a map."
	[args]
	(loop [remaining args
				 result {}]
		(if (empty? remaining)
			result
			(let [[flag value & more] remaining]
				(if (and flag (str/starts-with? flag "--") value)
					(recur more (assoc result (keyword (subs flag 2)) value))
					(recur (rest remaining) result))))))

(defn- read-json-url [url]
	(try
		(-> url
				slurp
				(json/parse-string true))
		(catch Exception _
			nil)))

(defn- strip-jsonc-comments [s]
	(let [chars (vec s)
				n (count chars)]
		(loop [i 0
					 out []
					 in-string? false
					 escaped? false
					 line-comment? false
					 block-comment? false]
			(if (>= i n)
				(apply str out)
				(let [c (nth chars i)
						nxt (when (< (inc i) n) (nth chars (inc i)))]
					(cond
						line-comment?
						(if (= c \newline)
							(recur (inc i) (conj out c) false false false block-comment?)
							(recur (inc i) out false false true block-comment?))

						block-comment?
						(if (and (= c \*) (= nxt \/))
							(recur (+ i 2) out false false false false)
							(recur (inc i) out false false false true))

						in-string?
						(cond
							escaped?
							(recur (inc i) (conj out c) true false false false)

							(= c \\)
							(recur (inc i) (conj out c) true true false false)

							(= c \")
							(recur (inc i) (conj out c) false false false false)

							:else
							(recur (inc i) (conj out c) true false false false))

						(and (= c \/) (= nxt \/))
						(recur (+ i 2) out false false true false)

						(and (= c \/) (= nxt \*))
						(recur (+ i 2) out false false false true)

						(= c \")
						(recur (inc i) (conj out c) true false false false)

						:else
						(recur (inc i) (conj out c) false false false false)))))))

(defn- strip-trailing-commas [s]
	(let [chars (vec s)
				n (count chars)]
		(loop [i 0
					 out []
					 in-string? false
					 escaped? false]
			(if (>= i n)
				(apply str out)
				(let [c (nth chars i)]
					(cond
						in-string?
						(cond
							escaped?
							(recur (inc i) (conj out c) true false)

							(= c \\)
							(recur (inc i) (conj out c) true true)

							(= c \")
							(recur (inc i) (conj out c) false false)

							:else
							(recur (inc i) (conj out c) true false))

						(= c \")
						(recur (inc i) (conj out c) true false)

						(= c \,)
						(let [j (loop [k (inc i)]
											(if (>= k n)
												n
												(let [ch (nth chars k)]
													(if (Character/isWhitespace ^char ch)
														(recur (inc k))
														k))))
								next-ch (when (< j n) (nth chars j))]
							(if (or (= next-ch \}) (= next-ch \]))
								(recur (inc i) out false false)
								(recur (inc i) (conj out c) false false)))

						:else
						(recur (inc i) (conj out c) false false)))))))

(defn- parse-jsonc [s]
	(-> s
			strip-jsonc-comments
			strip-trailing-commas
			(json/parse-string true)))

(defn- read-json-file [path]
	(let [f (io/file path)]
		(when (.exists f)
			(try
				(-> f
						slurp
						parse-jsonc)
				(catch Exception _
					nil)))))

(defn- configuration-properties [package-json]
	(let [configuration (get-in package-json [:contributes :configuration])]
		(cond
			(vector? configuration)
			(->> configuration
					 (map :properties)
					 (filter map?)
					 (apply merge {}))

			(map? configuration)
			(get configuration :properties {})

			:else
			{})))

(defn- calva-defaults [package-json]
	(let [properties (configuration-properties package-json)]
		(->> properties
				 (filter (fn [[k v]]
								 (and (str/starts-with? (key-name k) "calva.")
										(contains? v :default))))
				 (map (fn [[k v]] [(key-name k) (:default v)]))
				 (into (sorted-map)))))

(defn- calva-settings [settings]
	(->> settings
			 (filter (fn [[k _]] (str/starts-with? (key-name k) "calva.")))
			 (map (fn [[k v]] [(key-name k) v]))
			 (into (sorted-map))))

(defn- existing-paths [paths]
	(->> paths
			 (filter some?)
			 distinct
			 (map io/file)
			 (filter #(.exists %))
			 (map #(.getPath %))))

(defn- wsl-user-settings-candidates []
	(let [users-dir (io/file "/mnt/c/Users")]
		(when (.exists users-dir)
			(->> (.listFiles users-dir)
					 (filter #(.isDirectory %))
					 (map (fn [dir]
									(str (.getPath dir) "/AppData/Roaming/Code/User/settings.json")))))))

(defn- first-readable-json [paths]
	(some (fn [path]
					(when-let [settings (read-json-file path)]
						{:path path
						 :settings settings}))
				paths))

(defn- report []
	(let [{:keys [cwd user-settings windows-user-dir]} (parse-args *command-line-args*)
				working-dir (or cwd (System/getProperty "user.dir"))
				project-path (str (io/file working-dir ".vscode/settings.json"))
				home (System/getProperty "user.home")
				linux-user-path (str (io/file home ".config/Code/User/settings.json"))
				windows-user-path (when windows-user-dir
														(str (io/file windows-user-dir "AppData/Roaming/Code/User/settings.json")))
				user-candidates (existing-paths
												 (concat
													[user-settings linux-user-path windows-user-path]
													(wsl-user-settings-candidates)))
				package-json (read-json-url calva-package-json-url)
				defaults (if package-json (calva-defaults package-json) (sorted-map))
				project-data (first-readable-json [project-path])
				user-data (first-readable-json user-candidates)
				project-settings (if project-data (calva-settings (:settings project-data)) (sorted-map))
				user-settings-map (if user-data (calva-settings (:settings user-data)) (sorted-map))
				errors (cond-> []
							 (nil? package-json)
							 (conj (str "Could not fetch Calva defaults from " calva-package-json-url))
							 (nil? project-data)
							 (conj (str "Project settings not found/readable at " project-path))
							 (nil? user-data)
							 (conj "No readable user settings found in candidates"))]
		{:sources
		 {:calva-package-json calva-package-json-url
			:project-settings project-path
			:user-settings-candidates user-candidates}
		 :errors errors
		 :calva-defaults defaults
		 :project-settings project-settings
		 :user-settings user-settings-map
		 :project
		 (when project-data
			 {:path (:path project-data)
				:calva-settings (calva-settings (:settings project-data))})
		 :user
		 (when user-data
			 {:path (:path user-data)
				:calva-settings (calva-settings (:settings user-data))})}))

(defn- all-property-keys [{:keys [calva-defaults user-settings project-settings]}]
	(->> (concat (keys calva-defaults)
						 (keys user-settings)
						 (keys project-settings))
		 distinct
		 sort))

(defn- value-str [v]
	(if (nil? v) "" (pr-str v)))

(defn- property-line [data property]
	(let [default-v (get-in data [:calva-defaults property])
				user-v (get-in data [:user-settings property])
				project-v (get-in data [:project-settings property])]
		(str property
			 " | " (value-str default-v)
			 " | " (value-str user-v)
			 " | " (value-str project-v))))

(defn- format-report [data]
	(let [lines (->> (all-property-keys data)
								 (map (partial property-line data)))]
		(if (seq lines)
			(str/join "\n" lines)
			(str/join "\n"
						[(str "No calva.* properties found.")
						 (str "Tried defaults URL: " (get-in data [:sources :calva-package-json]))
						 (str "Tried project settings: " (get-in data [:sources :project-settings]))
						 (str "User settings candidates: " (str/join ", " (get-in data [:sources :user-settings-candidates])))]))))

(defn- print-errors! [errors]
	(doseq [e errors]
		(binding [*out* *err*]
			(println (str "WARN: " e)))))

(defn -main []
	(let [data (report)]
		(print-errors! (:errors data))
		(-> data
				format-report
				println)))
