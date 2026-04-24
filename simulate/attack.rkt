#lang htdp/isl+

;;;;;
;;;;; Combat Resolution
;;;;; Standalone simulation of resolve-combat
;;;;;

;;;;
;;;; Overview
;;;;

;;; Resolves a combat engagement between two players. All constants mirror those in constants.clj.
;;; Note: constants here use lower-case names by design, which is a deliberate departure from the 
;;; HtDP convention of ALL-CAPS for constants.


;;;;
;;;; Constants
;;;;

;;; Unit power values
(define soldier-power   1)
(define fighter-power   3)
(define cmd-ship-power 20)
(define station-power   5)  ; defender only
(define general-power   5)
(define admiral-power  10)

;;; Combat variance: each side's power is multiplied by a random factor
;;; drawn from [1 - combat-variance, 1 + combat-variance]
(define combat-variance 0.15)

;;; Capacity limits
(define soldiers-per-general    1000)
(define soldiers-per-transport   100)
(define fighters-per-admiral    1000)
(define fighters-per-carrier     100)


;;;;
;;;; Data Definitions
;;;;

(define-struct player
  (name
   soldiers transports generals
   fighters carriers  admirals
   cmd-ships stations
   mil-planets erg-planets ore-planets))
;;; A Player is a (make-player String
;;;                            Natural Natural Natural
;;;                            Natural Natural Natural
;;;                            Natural Natural
;;;                            Natural Natural Natural)
;;; where name         is the empire name
;;;       soldiers     is the number of ground troops
;;;       transports   is the number of troop transports
;;;       generals     is the number of generals commanding troops
;;;       fighters     is the number of fighter craft
;;;       carriers     is the number of fighter carriers
;;;       admirals     is the number of admirals commanding fighters
;;;       cmd-ships    is the number of command ships
;;;       stations     is the number of battle stations (defender only)
;;;       mil-planets  is the number of military planets held
;;;       erg-planets  is the number of energy planets held
;;;       ore-planets  is the number of ore planets held

;;; A Forces is a (make-forces Natural Natural Natural Natural Natural Natural Natural Natural)
;;; Represents the effective units a side brings to a battle.
(define-struct forces [soldiers fighters transports generals carriers admirals cmd-ships stations])

;;; A Losses is a (make-losses Natural Natural Natural Natural Natural Natural Natural Natural)
;;; Represents units destroyed in a battle.
(define-struct losses [soldiers transports generals fighters carriers admirals cmd-ships stations])

;;; A PlanetTransfer is a (make-planet-transfer Natural Natural Natural)
;;; Represents planets captured by the attacker.
(define-struct planet-transfer [mil erg ore])

;;; A CombatResult is a
;;;   (make-combat-result String String
;;;                       Forces Forces
;;;                       Number Number
;;;                       Boolean
;;;                       Number
;;;                       Losses Losses
;;;                       PlanetTransfer)
(define-struct combat-result [attacker-name   defender-name
                              attacker-forces defender-forces
                              attacker-roll   defender-roll
                              attacker-wins?
                              margin
                              attacker-losses defender-losses
                              planets-transferred])


;;;;
;;;; Wishes (Helper Functions)
;;;;

;;; take : (listof X) Natural -> (listof X)
;;;
;;; Produce the first n elements of lst.
;;;
;;; (define (take lst n) ...)
;;;
;;; (check-expect (take '(1 2 3 4) 2) '(1 2))
;;; (check-expect (take '(1 2 3)   0) '())
;;; (check-expect (take '()        3) '())
;;;
(define (take lst n)
  (cond [(= n 0)      '()]
        [(empty? lst) '()]
        [else 
          (cons (first lst) (take (rest lst) (- n 1)))]))

(check-expect (take '(1 2 3 4) 2) '(1 2))
(check-expect (take '(1 2 3)   0) '())
(check-expect (take '()        3) '())


;;; remove-nth : (listof X) Natural -> (listof X)
;;;
;;; Produce lst with the element at index n removed.
;;;
;;; (define (remove-nth lst n) ...)
;;;
;;; (check-expect (remove-nth '(a b c d) 0) '(b c d))
;;; (check-expect (remove-nth '(a b c d) 2) '(a b d))
;;;
(define (remove-nth lst n)
  (cond [(= n 0) (rest lst)]
        [else 
          (cons (first lst) (remove-nth (rest lst) (- n 1)))]))

(check-expect (remove-nth '(a b c d) 0) '(b c d))
(check-expect (remove-nth '(a b c d) 2) '(a b d))


;;; my-shuffle : (listof X) -> (listof X)
;;;
;;; Produce a random permutation of lst.
;;;
;;; (define (my-shuffle lst) ...)
;;;
(define (my-shuffle lst)
  (local [(define (shuffle-helper remaining)
            (cond [(empty? remaining) '()]
                  [else
                   (local [(define i      (random (length remaining)))
                           (define chosen (list-ref remaining i))]
                     (cons chosen
                           (shuffle-helper (remove-nth remaining i))))]))]
    (shuffle-helper lst)))


;;; count-where : (X -> Boolean) (listof X) -> Natural
;;;
;;; Count the elements of lst satisfying pred.
;;;
;;; (define (count-where pred lst) ...)
;;;
;;; (check-expect (count-where even? '(1 2 3 4 5 6)) 3)
;;; (check-expect (count-where odd?  '(2 4 6))        0)
;;;
(define (count-where pred lst)
  (length (filter pred lst)))

(check-expect (count-where even? '(1 2 3 4 5 6)) 3)
(check-expect (count-where odd?  '(2 4 6))       0)


;;;;
;;;; Randomness
;;;;

;;; roll : Number -> Number
;;;
;;; Multiply power by a random factor in [1 - combat-variance, 1 + combat-variance].
;;;
;;; (define (roll power) ...)
;;;
;;; (check-within (roll 1000) 1000.0 (* 1000 combat-variance))
;;;
(define (roll power)
  (* power
     (+ (- 1 combat-variance)
        (* (/ (random 1000000) 1000000)
           (* 2 combat-variance)))))

(check-within (roll 1000) 1000.0 (* 1000 combat-variance))


;;;;
;;;; Force Calculations
;;;;

;;; effective-forces : Player -> Forces
;;;
;;; Compute effective attacking forces, capped by transport and general capacity.
;;; Attackers do not contribute stations.
;;;
;;; (define (effective-forces p) ...)
;;;
(define (effective-forces p) (make-forces
   (min (player-soldiers  p)
        (* (player-transports p) soldiers-per-transport)
        (* (player-generals   p) soldiers-per-general))
   (min (player-fighters p)
        (* (player-carriers p) fighters-per-carrier)
        (* (player-admirals p) fighters-per-admiral))
   (player-transports p)
   (player-generals   p)
   (player-carriers   p)
   (player-admirals   p)
   (player-cmd-ships  p)
   0))  ; attackers have no stations


;;; effective-defending-forces : Player -> Forces
;;;
;;; Compute effective defending forces.  Defenders have no transport or carrier cap
;;; because troops are already on the ground.  Stations count only for defenders.
;;;
;;; (define (effective-defending-forces p) ...)
;;;
(define (effective-defending-forces p)
  (make-forces
   (min (player-soldiers    p)
        (* (player-generals p) soldiers-per-general))
   (min (player-fighters    p)
        (* (player-admirals p) fighters-per-admiral))
   (player-transports p)
   (player-generals   p)
   (player-carriers   p)
   (player-admirals   p)
   (player-cmd-ships  p)
   (player-stations   p)))


;;; Sample player for tests
(define small-player
  (make-player "Small" 500 3 2 300 5 2 1 0 5 3 2))

(check-expect (effective-forces small-player)
              (make-forces
               (min 500 (* 3 100) (* 2 1000))
               (min 300 (* 5 100) (* 2 1000))
               3 2 5 2 1 0))

(check-expect (effective-defending-forces small-player)
              (make-forces
               (min 500 (* 2 1000))
               (min 300 (* 2 1000))
               3 2 5 2 1 0))


;;;;
;;;; Power Calculation
;;;;

;;; base-power : Forces Boolean -> Number
;;;
;;; Sum the raw power of a set of forces.  Pass #true for attacker?, #false for defender. Stations
;;; contribute power only for defenders.
;;;
;;; (define (base-power f attacker?) ...)
;;;
;;; (check-expect (base-power sample-forces #t) ...)
;;; (check-expect (base-power sample-forces #f) ...)
;;;
(define (base-power f attacker?)
  (+ (* (forces-soldiers  f) soldier-power)
     (* (forces-fighters  f) fighter-power)
     (* (forces-cmd-ships f) cmd-ship-power)
     (* (forces-generals  f) general-power)
     (* (forces-admirals  f) admiral-power)
     (if attacker? 0 (* (forces-stations f) station-power))))

(define sample-forces (make-forces 100 50 5 3 4 2 1 2))

(check-expect (base-power sample-forces #t)
              (+ (* 100 1) (* 50 3) (* 1 20) (* 3 5) (* 2 10)))

(check-expect (base-power sample-forces #f)
              (+ (* 100 1) (* 50 3) (* 1 20) (* 3 5) (* 2 10) (* 2 5)))


;;;;
;;;; Loss Calculation
;;;;

;;; compute-losses : Forces Number -> Losses
;;;
;;; Apply a loss rate to each unit type.  Fractional losses are floored; the
;;; result is never negative.
;;;
;;; (define (compute-losses f rate) ...)
;;;
;;; (check-expect (compute-losses (make-forces 100 50 10 5 8 4 2 0) 0.5)
;;;               (make-losses 50 5 2 25 4 2 1 0))
;;; (check-expect (compute-losses (make-forces 100 50 10 5 8 4 2 0) 0)
;;;               (make-losses 0 0 0 0 0 0 0 0))
;;;
(define (compute-losses f rate)
  (local [(define (floor-loss n)
            (max 0 (inexact->exact (floor (* n rate)))))]
    (make-losses
     (floor-loss (forces-soldiers   f))
     (floor-loss (forces-transports f))
     (floor-loss (forces-generals   f))
     (floor-loss (forces-fighters   f))
     (floor-loss (forces-carriers   f))
     (floor-loss (forces-admirals   f))
     (floor-loss (forces-cmd-ships  f))
     (floor-loss (forces-stations   f)))))

(check-expect (compute-losses (make-forces 100 50 10 5 8 4 2 0) 0.5)
              (make-losses 50 5 2 25 4 2 1 0))

(check-expect (compute-losses (make-forces 100 50 10 5 8 4 2 0) 0)
              (make-losses 0 0 0 0 0 0 0 0))


;;;;
;;;; Planet Selection
;;;;

;;; select-planets : Player Natural -> PlanetTransfer
;;;
;;; Randomly select n planets from the defender's pool.
;;; If n exceeds the total planets held, all planets are taken.
;;;
;;; (define (select-planets defender n) ...)
;;;
;;; (check-expect (select-planets small-player 0)
;;;               (make-planet-transfer 0 0 0))
;;;
(define (select-planets defender n)
  (local [(define pool
            (my-shuffle
             (append (build-list (player-mil-planets defender) (lambda (i) 'mil))
                     (build-list (player-erg-planets defender) (lambda (i) 'erg))
                     (build-list (player-ore-planets defender) (lambda (i) 'ore)))))
          (define taken (take pool (min n (length pool))))]
    (make-planet-transfer
     (count-where (lambda (t) (symbol=? t 'mil)) taken)
     (count-where (lambda (t) (symbol=? t 'erg)) taken)
     (count-where (lambda (t) (symbol=? t 'ore)) taken))))

;;; When no planets are captured, all counts are 0
(check-expect (select-planets small-player 0)
              (make-planet-transfer 0 0 0))

;;; Total planets captured equals n exactly when the defender has enough
(define planet-test-result (select-planets small-player 4))

(check-expect (+ (planet-transfer-mil planet-test-result)
                 (planet-transfer-erg planet-test-result)
                 (planet-transfer-ore planet-test-result)) 4)


;;;;
;;;; Combat Resolution
;;;;

;;; resolve-combat : Player Player -> CombatResult
;;;
;;; Resolve a full combat engagement between attacker and defender.
;;; Each side's power is rolled with random variance.  The winner is determined
;;; by the higher roll.  The margin (a value in [0.0, 1.0]) drives loss rates
;;; and the number of planets captured.
;;;
;;; (define (resolve-combat attacker defender) ...)
;;;
(define (resolve-combat attacker defender)
  (local [(define att-forces (effective-forces attacker))
          (define def-forces (effective-defending-forces defender))
          (define att-power  (base-power att-forces #t))
          (define def-power  (base-power def-forces #f))
          (define att-roll   (roll att-power))
          (define def-roll   (roll def-power))
          (define att-wins?  (> att-roll def-roll))
          (define max-roll   (max att-roll def-roll))
          ;; Normalised relative difference in [0.0, 1.0]:
          ;; low margin = evenly matched; high margin = one side overwhelmed
          (define margin
            (if (zero? max-roll) 0
                (/ (abs (- att-roll def-roll)) max-roll)))
          ;; Loser takes losses proportional to margin, capped at 75%
          (define loser-rate  (min margin 0.75))
          ;; Winner always pays some cost: half the loser's rate
          (define winner-rate (/ loser-rate 2))
          (define def-total-planets
            (+ (player-mil-planets defender)
               (player-erg-planets defender)
               (player-ore-planets defender)))
          ;; Planets captured scale with margin; none captured on a loss
          (define planets-count
            (if att-wins?
                (inexact->exact (floor (* margin def-total-planets)))
                0))]
    (make-combat-result
     (player-name attacker)
     (player-name defender)
     att-forces
     def-forces
     att-roll
     def-roll
     att-wins?
     margin
     (compute-losses att-forces (if att-wins? winner-rate loser-rate))
     (compute-losses def-forces (if att-wins? loser-rate  winner-rate))
     (select-planets defender planets-count))))


;;;;
;;;; Example
;;;;

(define attacker
  (make-player "Attacker"
               5000  ; soldiers
               20    ; transports
               8     ; generals
               3000  ; fighters
               15    ; carriers
               5     ; admirals
               2     ; cmd-ships
               0     ; stations
               10 8 6))

(define defender
  (make-player "Defender"
               4000  ; soldiers
               10    ; transports
               6     ; generals
               2000  ; fighters
               10    ; carriers
               4     ; admirals
               1     ; cmd-ships
               3     ; stations
               8 6 4))

(resolve-combat attacker defender)
