(ns nodename.stately.statechart
  (:require [clojure.string :refer [blank?]]
            [re-frame.core :as re-frame :refer [register-handler]]
            [re-frame.utils :refer [log warn error first-in-vector]]
            [com.rpl.specter :as s]
            [cljs.pprint :refer [pprint]]
            [nodename.stately.core :refer [dispatch
                                           init-active
                                           set-active active-state
                                           set-state-tree
                                           active-components
                                           lca-path
                                           show-state-error]]))





;; ACTIONS ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



(defn register-action-handlers
  [middleware {:keys [all-actions] :as chart-data}]
  (let [all-actions (merge all-actions)]
    (doseq [[trigger action] all-actions]
      (register-handler trigger
                        middleware
                        (if (fn? action)
                          action
                          ;; vector: fn and args:
                          (fn [db values]
                            ((first action)
                              db (concat (rest action) values))))))))



;; ACTIVITIES ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- start-action
  "Create an action keyword for starting an activity"
  [activity]
  (keyword (namespace activity)
           (str (name activity) ".START-ACTION")))

(defn- stop-action
  "Create an action keyword for stopping an activity"
  [activity]
  (keyword (namespace activity)
           (str (name activity) ".STOP-ACTION")))

(defn register-activity-handlers
  [middleware {:keys [all-states all-activities] :as chart-data}]
  (doseq [[trigger state] all-states]
    (let [{activities :activities
           :or {:activities []}} state]
      (doseq [activity activities]
        (let [{start :start
               stop :stop} (get all-activities activity)]
          (when start
            (let [action (start-action activity)]
              (register-handler action
                                middleware
                                (fn [db]
                                  (dispatch start)
                                  db))))
          (when stop
            (let [action (stop-action activity)]
              (register-handler action
                                middleware
                                (fn [db]
                                  (dispatch stop)
                                  db)))))))))



;; TRANSITIONS ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn start-activities
  [state-data]
  (doseq [activity (:activities state-data)]
    (dispatch [(start-action activity)])))

(defn show-condition-not-met
  [trigger current-state condition]
  (log (str "Transition " trigger
            " in state " current-state
            " failed condition " condition)))


(defn component->entry-action
  [component]
  [[(keyword (name component) "none") (keyword (name component) "ENTRY-TRANSITION")]])

(defn stop-activity-actions
  [state-data]
  (map stop-action (:activities state-data)))

(defn all-exit-actions
  [state all-states]
  (let [state-data (get all-states state)]
    (concat (stop-activity-actions state-data)
            (:exit-actions state-data))))

(defn components-entry-actions
  [state-data]
  (map component->entry-action (get state-data :components)))

(defn start-activity-actions
  [state-data]
  (map start-action (:activities state-data)))

(defn all-entry-actions
  [state all-states]
  (let [state-data (get all-states state)]
    (concat (:entry-actions state-data)
            (components-entry-actions state-data)
            (start-activity-actions state-data))))


(defn perform-transition
  [db state-and-trigger transition current-state values all-states]
  (warn "PERFORMING " state-and-trigger " current-state: " current-state)
  (let [[start-state trigger] state-and-trigger

        {target :target
         actions :actions
         :or {actions []}} transition

        ;; handle target that is a function:
        target (if (fn? target)
                 (target db values)
                 target)
        _ (warn "target: " target)


        _ (warn "ACTIVE COMPONENTS:")
        _ (warn (active-components current-state all-states))

        ;; Unset states of all subcomponents of current-state, starting from the leaves:
        ;; TODO stop their activities and do their exit actions too
        ;; (revisit this when implementing history)
        components (active-components current-state all-states)
        _ (doseq [component components]
            (let [st (active-state component)]
              (set-active st false)))


        [exit-path entry-path] (cond (= (name trigger) "ENTRY-TRANSITION") [(if current-state [current-state] []) [target]]
                                     :else (lca-path current-state target))
        _ (warn "exit-path: " exit-path " entry-path: " entry-path)
        exit-path-actions (mapcat #(all-exit-actions % all-states) exit-path)
        _ (warn "exit path actions: " exit-path-actions)
        entry-path-actions (mapcat #(all-entry-actions % all-states) entry-path)
        _ (warn "entry-path-actions: " entry-path-actions)

        ;; atomic change of state: exit actions followed immediately by entry actions;
        ;; explicit actions take place in the context of the target state
        actions (concat exit-path-actions
                        entry-path-actions
                        actions)]

    (doseq [state exit-path]
      (set-active state false))

    ;; each action gets my values appended after any explicit values it carries:
    (doseq [action actions]
      (let [action (if values
                     (vec (concat action values))
                     action)]
        (dispatch action)))

    (doseq [state entry-path]
      (set-active state true))
    db))


(defn state-eligible?
  [current-state start-state]
  (println "current state:" current-state " start state: " start-state)
  (or (and (= "none" (name start-state))
           (or (blank? current-state) (= "none" (name current-state))))
      (= :any start-state)
      (= current-state start-state)))


(defn make-transition-handler
  [all-states]
  (fn [state-and-trigger transition]
    (warn "MTH: state-and-trigger: " state-and-trigger)

    (fn [db & [values]]
      (warn "state-and-trigger: " state-and-trigger)
      (let [transition-state (first state-and-trigger)
            trigger (second state-and-trigger)
            fsm-name (or (namespace transition-state)
                         (namespace trigger))
            current-state (active-state fsm-name)]
        (warn "MTH: transition-state: " transition-state " current-state: " current-state)

        (let [eligible? (state-eligible? current-state transition-state)

              {condition :condition
               :or {condition (constantly true)}} transition]
          (warn "MTH: transition: " transition)
          (warn "MTH: eligible? " eligible?)
          (if (and eligible?
                   (condition db values))
            (perform-transition db state-and-trigger transition
                                current-state values all-states)
            (do
              #_(show-state-error state-and-trigger current-state)
              db)))))))


(defn add-entry-transition
  [acc start-state]
  (let [fsm-name (namespace start-state)
        trigger (keyword fsm-name "ENTRY-TRANSITION")
        transition {:target start-state}]
    (merge acc {[(keyword fsm-name "none") trigger] transition})))

(defn make-entry-transitions
  "Create a synthetic entry transition for each state machine
  that will cause it to go into its declared start-state"
  [all-start-states]
  (reduce add-entry-transition {} all-start-states))


(defn register-transition-handlers
  [middleware {:keys [all-transitions all-states all-start-states]}]
  (println "RTH")
  (let [make-transition-handler (make-transition-handler all-states)
        entry-transitions (make-entry-transitions all-start-states)
        all-transitions (merge all-transitions entry-transitions)]
    (doseq [[trigger transition] all-transitions]
      (let [transitions [transition]
            make-transition-handler #(make-transition-handler trigger %)
            handler (apply comp (map make-transition-handler transitions))]
        (register-handler trigger
                          middleware
                          handler)))))


;; MAIN ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn- merge-no-clobber
  "Merge the maps but go nuts if a key is repeated"
  [& maps]
  (let [f (fn [val0 val1] (error "Clobbering a handler! " val0 " " val1))]
    (apply merge-with f maps)))

(defn- merge-with-concat
  [& maps]
  (let [f (fn [val0 val1] [val0 val1])]
    (apply merge-with f maps)))


(defn register-handlers
  [middleware state-machines]
  (let [fsms (vals state-machines)
        obtain (fn [prop]
                 (apply merge-no-clobber
                        (map #(get % prop) fsms)))

        all-actions (obtain :actions)
        all-activities (obtain :activities)
        all-transitions (apply merge-with-concat
                               (map #(get % :transitions) fsms))
        all-states (obtain :states)
        all-start-states (remove nil? (map #(get % :start-state) fsms))

        chart-data {:all-actions all-actions
                    :all-activities all-activities
                    :all-transitions all-transitions
                    :all-states all-states
                    :all-start-states all-start-states}]

    (set-state-tree state-machines)
    (init-active)

    (register-action-handlers middleware chart-data)
    (register-activity-handlers middleware chart-data)
    (register-transition-handlers  middleware chart-data)))
