(ns tech.ml
  (:require [tech.ml.registry :as registry]
            [tech.ml.protocols.system :as system-proto]
            [tech.ml.dataset :as dataset]
            [tech.ml.gridsearch :as ml-gs]
            [tech.parallel :as parallel]
            [tech.datatype :as dtype]
            [clojure.set :as c-set]
            [tech.ml.utils :as utils])
  (:import [java.util UUID]))


(defn- normalize-column-seq
  [col-seq]
  (cond
    (sequential? col-seq)
    (vec col-seq)
    (set? col-seq)
    (vec col-seq)
    (or (keyword? col-seq)
        (string? col-seq))
    [col-seq]
    :else
    (throw (ex-info (format "Failed to normalize column sequence %s" col-seq)
                    {:column-seq col-seq}))))


(defn train
  "Train a model.  Returns a map of:
  {:model - direct result of system-proto/train
   :options - options used to train model
   :id - random UUID generated}"
  ([options dataset]
   (when-not (> (count (:feature-columns options)) 0)
     (throw (ex-info "Must be at least one feature column to train"
                     {:feature-columns (get options :feature-columns)})))
   (when-not (> (count (:label-columns options)) 0)
     (throw (ex-info "Must be at least one label column to train"
                     {:label-columns (get options :label-columns)})))
   (let [ml-system (registry/system (:model-type options))
         model (system-proto/train ml-system options (dataset/->dataset dataset {}))]
     {:model model
      :options options
      :id (UUID/randomUUID)}))
  ([options feature-columns label-columns dataset]
   (train (assoc options
                 :feature-columns (normalize-column-seq feature-columns)
                 :label-columns (normalize-column-seq label-columns))
          dataset)))


(defn predict
  "Generate a sequence of predictions (inferences) from a training result.

  If classification, returns a sequence of probability distributions
  if possible.  If not, returns a map the selected option as the key and
  the probabily as the value.

  If regression, returns the sequence of predicated values."
  [{:keys [options model] :as train-result} dataset]
  (when-not (> (count (:feature-columns options)) 0)
     (throw (ex-info "Must be at least one feature column to train"
                     {:feature-columns (get options :feature-columns)})))
  (let [ml-system (registry/system (:model-type options))]
    (system-proto/predict ml-system options model dataset)))


(defn dataset-seq->dataset-model-seq
  "Given a sequence of {:train-ds ...} datasets, produce a sequence of:
  {:model ...}
  train-ds is removed to keep memory usage as low as possible.
  See dataset/dataset->k-fold-datasets"
  [options dataset-seq]
  (->> dataset-seq
       (map (fn [{:keys [train-ds] :as dataset-entry}]
              (let [{model :retval
                     train-time :milliseconds}
                    (utils/time-section (train options train-ds))]
                (-> (merge (assoc model :train-time train-time)
                           (dissoc dataset-entry :train-ds))))))))


(defn average-prediction-error
  "Average prediction error across models generated with these datasets
  Page 242, https://web.stanford.edu/~hastie/ElemStatLearn/.
  Result is the best train result annoted with the average loss of the group
  and total train and predict times."
  [options loss-fn dataset-seq]
  (when-not (seq dataset-seq)
    (throw (ex-info "Empty dataset sequence" {:dataset-seq dataset-seq})))
  (let [train-predict-data
        (->> (dataset-seq->dataset-model-seq options dataset-seq)
             (map (fn [{:keys [test-ds options model] :as train-result}]
                    (let [{predictions :retval
                           predict-time :milliseconds}
                          (utils/time-section (predict train-result test-ds))
                          labels (dataset/labels test-ds options)]
                      (merge (dissoc train-result :test-ds)
                             {:predict-time predict-time
                              :loss (loss-fn predictions labels)})))))
        ds-count (count dataset-seq)
        ave-fn #(* (/ 1.0 ds-count) %)
        total-seq #(apply + 0 %)
        total-loss (total-seq (map :loss train-predict-data))
        total-predict (total-seq (map :predict-time train-predict-data))
        total-train (total-seq (map :train-time train-predict-data))]
    (merge (->> train-predict-data
                (sort-by :loss)
                first)
           {:average-loss (ave-fn total-loss)
            :total-train-time total-train
            :total-predict-time total-predict})))


(defn auto-gridsearch-options
  [options]
  (let [ml-system (registry/system (:model-type options))]
    (merge options
           (system-proto/gridsearch-options ml-system options))))


;;The gridsearch error reporter is called when there is an error during gridsearch.
;;It is called like so:
;;(*gridsearch-error-reporter options-map error)
(def ^:dynamic *gridsearch-error-reporter* nil)


(defn gridsearch
  "Gridsearch these system/option pairs by this dataset, averaging the errors
  across k-folds and taking the lowest top-n options.
We are breaking out of 'simple' and into 'easy' here, this is pretty
opinionated.  The point is to make 80% of the cases work great on the
first try."
  [{:keys [parallelism top-n gridsearch-depth k-fold]
    :or {parallelism (.availableProcessors
                      (Runtime/getRuntime))
         top-n 5
         gridsearch-depth 50
         k-fold 5}
    :as options}
   loss-fn dataset]
  ;;Should do row-major conversion here and make it work later.  We specifically
  ;;know the feature and labels can't change.
  (let [dataset-seq (if (and k-fold (> (int k-fold) 1))
                      (vec (dataset/->k-fold-datasets dataset k-fold options))
                      [(dataset/->train-test-split dataset options)])]
    (->> (ml-gs/gridsearch options)
         (take gridsearch-depth)
         (parallel/queued-pmap
          parallelism
          (fn [options-map]
            (try
              (average-prediction-error options-map
                                        loss-fn
                                        dataset-seq)
              (catch Throwable e
                (when *gridsearch-error-reporter*
                  (*gridsearch-error-reporter* options-map e))
                nil))))
         (remove nil?)
         ;;Partition to keep sorting time down a bit.
         (partition-all top-n)
         (reduce (fn [best-items next-group]
                   (->> (concat best-items next-group)
                        (sort-by :average-loss)
                        (take top-n)))
                 []))))
