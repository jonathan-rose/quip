(ns quip.collision)

(defn equal-pos?
  "Predicate to check if two sprites have the same position."
  [a b]
  (and (seq (:pos a))
       (seq (:pos b))
       (every? true? (map = (:pos a) (:pos b)))))

(defn w-h-rects-collide?
  "Predicate to check if the `w` by `h` rects of two sprites intersect."
  [{[ax1 ay1] :pos
    aw      :w
    ah      :h}
   {[bx1 by1] :pos
    bw      :w
    bh      :h}]
  ;; @TODO: should we be drawing spriters at their center? if so, this
  ;; should take it into account.
  (let [ax2 (+ ax1 aw)
        ay2 (+ ay1 ah)
        bx2 (+ bx1 bw)
        by2 (+ by1 bh)]
    (or (and (<= ax1 bx1 ax2)
             (or (<= ay1 by1 ay2)
                 (<= ay1 by2 ay2)))
        (and (<= ax1 bx2 ax2)
             (or (<= ay1 by1 ay2)
                 (<= ay1 by2 ay2))))))

;; @TODO: implement the following:

(defn pos-in-rect?
  "Predicate to check if the position of sprite `a` is inside the `w` by
  `h` rect of sprite `b`."
  [a b]
  (throw (new Exception "Unimplemented collision detection function")))
(defn rect-contains-pos?
  "Predicate to check if the position of sprite `b` is inside the `w` by
  `h` rect of sprite `a`."
  [a b]
  (point-in-rect? b a))

(defn pos-in-poly?
  "Predicate to check if the position of sprite `a` is inside the
  bounding polygon of sprite `b`."
  [a b]
  (throw (new Exception "Unimplemented collision detection function")))
(defn poly-contains-pos?
  "Predicate to check if the position of sprite `b` is inside the
  bounding polygon of sprite `a`."
  [a b]
  (point-in-poly? b a))

(defn pos-in-rotating-poly?
  "Predicate to check if the position of sprite `a` is inside the
  bounding polygon of sprite `b`, taking into account the rotation of
  sprite `b`."
  [a b]
  (throw (new Exception "Unimplemented collision detection function")))
(defn rotating-poly-contains-pos?
  "Predicate to check if the position of sprite `b` is inside the
  bounding polygon of sprite `a`, taking into account the rotation of
  sprite `a`."
  [a b]
  (point-in-rotating-poly? b a))

(defn collider
  "Define a check for collision between to groups of sprites with
  functions to be invoked on the sprites when collision is detected."
  [group-a-key group-b-key collide-fn-a collide-fn-b &
   {:keys [collision-detection-fn]
    :or   {collision-detection-fn w-h-rects-collide?}}]
  {:group-a-key            group-a-key
   :group-b-key            group-b-key
   :collision-detection-fn collision-detection-fn
   :collide-fn-a           collide-fn-a
   :collide-fn-b           collide-fn-b})

(defn collide-sprites
  "Check two sprites for collision and update them with the appropriate
  `collide-fn-<x>` provided by the collider.

  In the case that we're checking a group of sprites for collisions in
  the same group we need to check the uuid on the sprites to ensure
  they're not colliding with themselves."
  [a b {:keys [group-a-key
               group-b-key
               collision-detection-fn
               collide-fn-a
               collide-fn-b]}]
  (let [collision-predicate (if (= group-a-key group-b-key)
                              (and (not= (:uuid a) (:uuid b))
                                   (collision-detection-fn a b))
                              (collision-detection-fn a b))]
    (if collision-predicate
      {:a (collide-fn-a a)
       :b (collide-fn-b b)}
      {:a a
       :b b})))

(defn collide-group
  "Check a sprite from one group for collisions with all sprites from
  another group, updating both as necessary.

  Reducing over group-b lets us build up a new version of group-b,
  updating the value of a as we go.

  We filter out any b that returns `nil` after colliding to allow
  collide functions to kill sprites."
  [a group-b collider]
  (reduce (fn [acc b]
            (let [results (collide-sprites (:a acc) b collider)]
              (-> acc
                  (assoc :a (:a results))
                  (update :group-b #(->> (conj % (:b results))
                                         (filter some?)
                                         vec)))))
          {:a       a
           :group-b []}
          group-b))

(defn collide-groups
  "Check a group of sprites for collisions with another group of
  sprites, updating all sprites as necessary.

  We're iterating using a reducing function over the first group, this
  means that each time we check an `a` against `group-b` we get the
  new value for a, and the new values for each sprite in `group-b`.

  We filter out any a that returns `nil` after colliding to allow
  collide functions to kill sprites.

  We build our results map using the threading macro to handle the
  case where `group-a-key` and `group-b-key` are the same."
  [sprite-groups {:keys [group-a-key group-b-key]
                  :as   collider}]
  (let [group-a (group-a-key sprite-groups)
        group-b (group-b-key sprite-groups)

        results (reduce (fn [acc a]
                          (let [group-result (collide-group a (:group-b acc) collider)]
                            (-> acc
                                (assoc :group-b (:group-b group-result))
                                (update :group-a #(->> (conj % (:a group-result))
                                                       (filter some?)
                                                       vec)))))
                        {:group-a []
                         :group-b group-b}
                        group-a)]

    (-> {}
        (assoc group-b-key (:group-b results))
        (assoc group-a-key (:group-a results)))))

(defn update-collisions
  "Update the sprites in the current scene based on the scene colliders."
  [{:keys [current-scene] :as state}]
  (let [sprites               (get-in state [:scenes current-scene :sprites])
        sprite-groups         (group-by :sprite-group sprites)
        colliders             (get-in state [:scenes current-scene :colliders])
        colliding-group-keys  (set (mapcat (juxt :group-a-key :group-b-key)
                                           colliders))
        colliding-groups      (select-keys sprite-groups colliding-group-keys)
        non-colliding-sprites (remove #(colliding-group-keys (:sprite-group %)) sprites)]
    (assoc-in state [:scenes current-scene :sprites]
              (concat non-colliding-sprites

                      (->> colliders
                           (reduce (fn [acc-groups {:keys [group-a-key group-b-key]
                                                    :as   collider}]
                                     (let [results (collide-groups acc-groups collider)]
                                       (-> acc-groups
                                           (assoc group-b-key (:b results))
                                           (assoc group-a-key (:a results)))))
                                   colliding-groups)
                           vals
                           (apply concat))))))
