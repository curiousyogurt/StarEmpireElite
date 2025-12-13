;;;;;
;;;;; Star Empire Elite - Main Application Entry Point
;;;;; 
;;;;; This file serves as the main entry point for the Star Empire Elite web application, built using
;;;;; the Biff web framework. Biff is a Clojure framework that provides structure and utilities for
;;;;; building web applications on top of libraries such as Ring, Reitit (routing), XTDB (database),
;;;;; and others.
;;;;;
;;;;; For newcomers to Biff: This framework emphasises a system-based architecture where components
;;;;; are started in order and configured through a central system map. The modular approach makes
;;;;; applications easier to understand and test.
;;;;;

(ns com.star-empire-elite
  (:require [com.biffweb :as biff]
            ;; Application-specific modules
            [com.star-empire-elite.email :as email]
            [com.star-empire-elite.app :as app]
            [com.star-empire-elite.home :as home]
            [com.star-empire-elite.middleware :as mid]
            [com.star-empire-elite.ui :as ui]
            [com.star-empire-elite.worker :as worker]
            [com.star-empire-elite.schema :as schema]
            ;; Standard Clojure libraries
            [clojure.test :as test]
            [clojure.tools.logging :as log]
            [clojure.tools.namespace.repl :as tn-repl]
            ;; Malli is used for data validation and schema definition
            [malli.core :as malc]
            [malli.registry :as malr]
            ;; nREPL for interactive development
            [nrepl.cmdline :as nrepl-cmd])
  ;; Generate a main class for standalone execution
  (:gen-class))

;;;;
;;;; Key Biff Concepts:
;;;;
;;;; 1. Modules: Self-contained units with routes, middleware, schemas, etc.
;;;;    They make applications modular and easier to understand.
;;;;
;;;; 2. Components: Infrastructure services (database, web server, etc.)
;;;;    that start in order and can depend on each other.
;;;;
;;;; 3. System Map: A single map containing all application state and config.
;;;;    This makes applications easier to test and reason about.
;;;;
;;;; 4. Hot Reloading: Code changes are reflected immediately without restart,
;;;;    which significantly speeds up the development workflow.
;;;;
;;;; 5. XTDB: A document database that stores immutable data with time travel
;;;;    capabilities, perfect for audit trails and complex queries.
;;;;

;;;;
;;;; Module Configuration
;;;; 
;;;; Modules are maps that contain routing, middleware, and other configuration. They're the main way
;;;; to organize functionality into self-contained units that can be composed together.
;;;;

(def modules
  [app/module                           ; Main application routes and logic
   (biff/authentication-module {})      ; Built-in Biff auth (login/logout/signup)
   home/module                          ; Home page routes
   schema/module                        ; Data schemas and validation
   worker/module])                      ; Background job processing

;;;;
;;;; Routing Configuration
;;;;
;;;; Biff uses Reitit for routing. This creates two main route groups: site routes for html pages, and
;;;; API routes for json endpoints.
;;;;

;;; Route definition combining all module routes with appropriate middleware. This structure separates
;;; site routes (html) from API routes (json).
(def routes [["" {:middleware [mid/wrap-site-defaults]}
              ;; Extract :routes key from each module and combine them. 'keep' removes nil values if a
              ;; module doesn't have routes
              (keep :routes modules)]
             ["" {:middleware [mid/wrap-api-defaults]}
              ;; Same for API routes: these typically return JSON
              (keep :api-routes modules)]])

;;; Main Ring handler that processes all HTTP requests. Biff's reitit-handler converts the route
;;; structure into a Ring handler, and wrap-base-defaults adds common middleware like security
;;; headers.
(def handler (-> (biff/reitit-handler {:routes routes})
                 mid/wrap-base-defaults))

;;; Pre-generated html pages that don't require server processing. These are useful for performance
;;; since pages can be served directly by a cdn. biff/safe-merge combines maps, throwing an error if
;;; keys conflict.
(def static-pages (apply biff/safe-merge (map :static modules)))

;;;;
;;;; Asset Generation and Development Workflow
;;;;
;;;; These functions handle static asset generation and the development hot-reloading workflow that
;;;; makes Biff development so pleasant.
;;;;

;;; Converts dynamic content to static files for better performance
(defn generate-assets! [ctx]
  ;; Export Rum components (Biff's html templating) to static html files
  (biff/export-rum static-pages "target/resources/public")
  ;; Clean up old html files to prevent stale content
  (biff/delete-old-files {:dir "target/resources/public"
                          :exts [".html"]}))

;;; Development hot-reload function called whenever files change
(defn on-save [ctx]
  ;; Hot-reload: Add new dependencies without restarting the system
  (biff/add-libs ctx)
  ;; Re-evaluate changed Clojure files to pick up code changes
  (biff/eval-files! ctx)
  ;; Regenerate static assets to reflect any template changes
  (generate-assets! ctx)
  ;; Run tests automatically to give immediate feedback on changes
  (test/run-all-tests #"com.star-empire-elite.*-test"))

;;;;
;;;; Data Validation Configuration
;;;;
;;;; Malli is used throughout Biff applications for data validation and
;;;; schema definition. This provides runtime safety and documentation.
;;;;

;; Malli configuration combining default schemas with application-specific ones. This registry is used
;; throughout the app for validating data.
(def malli-opts
  {:registry (malr/composite-registry
              ;; Default Malli schemas (string, int, keyword, etc.)
              malc/default-registry
              ;; Custom schemas from application modules
              (apply biff/safe-merge (keep :schema modules)))})

;;;;
;;;; System Configuration
;;;;
;;;; The system map is the heart of a Biff application. It contains all configuration and state,
;;;; making the application easier to test and reason about.
;;;;

;;; Initial system configuration defining how Biff should set up the application. The #' creates var
;;; references, allowing hot-reloading during development.
(def initial-system
  {:biff/modules #'modules                 ; Application modules
   :biff/send-email #'email/send-email     ; Email sending function
   :biff/handler #'handler                 ; Main HTTP handler
   :biff/malli-opts #'malli-opts           ; Data validation config
   :biff.beholder/on-save #'on-save        ; File watch callback for development
   :biff.middleware/on-error #'ui/on-error ; Error page rendering
   :biff.xtdb/tx-fns biff/tx-fns           ; Database transaction functions
   ;; Application-specific state - WebSocket clients for real-time features
   :com.star-empire-elite/chat-clients (atom #{})})

;;; Global system state holder. defonce ensures this atom is created only once, preserving state
;;; during repl sessions and hot reloads.
(defonce system (atom {}))

;;;;
;;;; Component Configuration
;;;;
;;;; Components are Biff's way of managing infrastructure services like databases,
;;;; web servers, and background job processors. They start in a specific order and can
;;;; depend on each other.
;;;;

;;; System components that provide infrastructure services. Each component is a function that takes
;;; the system map and adds functionality to it.
(def components
  [biff/use-aero-config        ; Configuration file loading (config.edn)
   biff/use-xtdb               ; XTDB database setup and connection
   biff/use-queues             ; Background job queues for async processing
   biff/use-xtdb-tx-listener   ; Database change notifications
   biff/use-htmx-refresh       ; HTMX development auto-refresh
   biff/use-jetty              ; Jetty web server
   biff/use-chime              ; Scheduled tasks (cron-like functionality)
   biff/use-beholder])         ; File system watching for development

;;;;
;;;; System Lifecycle Functions
;;;;
;;;; These functions handle starting, stopping, and restarting the application system during
;;;; development and production deployment.
;;;;

;;; Initializes and starts all system components in the correct order
(defn start []
  ;; Components are applied in order using reduce. Each component function receives the system map
  ;; and returns an updated version with new functionality.
  (let [new-system (reduce (fn [system component]
                             (log/info "starting:" (str component))
                             ;; Each component adds its functionality to the system
                             (component system))
                           initial-system
                           components)]
    ;; Update the global system state with the fully initialized system
    (reset! system new-system)
    ;; Generate initial static assets for immediate availability
    (generate-assets! new-system)
    (log/info "System started.")
    ;; Show the URL where the application is running
    (log/info "Go to" (:biff/base-url new-system))
    new-system))

;;; Main entry point used when running as a standalone application
(defn -main []
  ;; Start the system and extract nREPL configuration for remote development
  (let [{:keys [biff.nrepl/args]} (start)]
    ;; Start nREPL server for remote development and debugging
    (apply nrepl-cmd/-main args)))

;;; Development helper that restarts the system with code changes; this is typically called from the
;;; repl during development sessions
(defn refresh []
  ;; Stop all running components gracefully by calling their cleanup functions
  ;; :biff/stop contains cleanup functions added by components during startup
  (doseq [f (:biff/stop @system)]
    (log/info "stopping:" (str f))
    (f))
  ;; Reload changed namespaces using tools.namespace, which handles dependency
  ;; ordering automatically, then restart the system
  (tn-repl/refresh :after `start)
  :done)

