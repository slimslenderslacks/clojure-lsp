(ns lsp4clj.core
  (:require
   [clojure.core.async :refer [<! go-loop thread timeout]]
   [lsp4clj.coercer :as coercer]
   [lsp4clj.feature :as feature]
   [lsp4clj.producer :as producer]
   [lsp4clj.shared :as shared]
   [taoensso.timbre :as log])
  (:import
   (java.util.concurrent CompletableFuture
                         CompletionException)
   (java.util.function Supplier)
   (lsp4clj.feature ILSPFeature)
   (org.eclipse.lsp4j
     ApplyWorkspaceEditParams
     CallHierarchyIncomingCallsParams
     CallHierarchyOutgoingCallsParams
     CallHierarchyPrepareParams
     CodeActionParams
     CodeLens
     CodeLensParams
     CodeLensWorkspaceCapabilities
     CompletionItem
     CompletionParams
     DeclarationParams
     DefinitionParams
     DidChangeConfigurationParams
     DidChangeTextDocumentParams
     DidChangeWatchedFilesParams
     DidChangeWatchedFilesRegistrationOptions
     DidCloseTextDocumentParams
     DidOpenTextDocumentParams
     DidSaveTextDocumentParams
     DocumentFormattingParams
     DocumentHighlightParams
     DocumentRangeFormattingParams
     DocumentSymbolParams
     ExecuteCommandParams
     FileSystemWatcher
     HoverParams
     ImplementationParams
     InitializeParams
     InitializeResult
     InitializedParams
     LinkedEditingRangeParams
     MessageActionItem
     PrepareRenameParams
     ReferenceParams
     Registration
     RegistrationParams
     RenameParams
     SemanticTokensParams
     SemanticTokensRangeParams
     SignatureHelpParams
     WindowClientCapabilities
     WorkspaceSymbolParams)
   (org.eclipse.lsp4j.jsonrpc ResponseErrorException)
   (org.eclipse.lsp4j.services LanguageClient LanguageServer TextDocumentService WorkspaceService))
  (:gen-class))

(set! *warn-on-reflection* true)

(defonce formatting (atom false))

(defmacro start [id & body]
  `(let [~'_start-time (System/nanoTime)
         ~'_id ~id]
     (do ~@body)))

(defmacro end
  ([expr]
   `(end ~expr false))
  ([expr extra-log-fn]
   `(let [~'_result (try
                      ~expr
                      (catch Throwable ex#
                        (if (instance? ResponseErrorException ex#)
                          (throw (CompletionException. ex#))
                          (log/error ex#))))]
      (try
        (let [duration# (quot (- (System/nanoTime) ~'_start-time) 1000000)]
          (if ~extra-log-fn
            (log/debug ~'_id (format "%sms - %s" duration# (~extra-log-fn ~'_result)))
            (log/debug ~'_id (format "%sms" duration#))))
        (catch Throwable ex#
          (log/error ex#)))
      ~'_result)))

(defmacro sync-notification
  [params f handler]
  `(end
     (->> ~params
          coercer/java->clj
          (~f ~handler))))

(defmacro sync-request
  ([params f handler response-spec]
   `(sync-request ~params ~f ~handler ~response-spec false))
  ([params f handler response-spec extra-log-fn]
   `(end
      (->> ~params
           coercer/java->clj
           (~f ~handler)
           (coercer/conform-or-log ~response-spec))
      ~extra-log-fn)))

(defmacro ^:private async-request
  ([params f handler response-spec]
   `(async-request ~params ~f ~handler ~response-spec false))
  ([params f handler response-spec extra-log-fn]
   `(CompletableFuture/supplyAsync
      (reify Supplier
        (get [this]
          (sync-request ~params ~f ~handler ~response-spec ~extra-log-fn))))))

(defmacro ^:private async-notification
  [params f handler]
  `(CompletableFuture/supplyAsync
     (reify Supplier
       (get [this]
         (sync-notification ~params ~f ~handler)))))

(deftype LSPTextDocumentService [handler]
  TextDocumentService
  (^void didOpen [_ ^DidOpenTextDocumentParams params]
    (start :didOpen
           (sync-notification params feature/did-open handler)))

  (^void didChange [_ ^DidChangeTextDocumentParams params]
    (start :didChange
           (sync-notification params feature/did-change handler)))

  (^void didSave [_ ^DidSaveTextDocumentParams params]
    (start :didSave
           (future
             (sync-notification params feature/did-save handler)))
    (CompletableFuture/completedFuture 0))

  (^void didClose [_ ^DidCloseTextDocumentParams params]
    (start :didClose
           (async-notification params feature/did-close handler)))

  (^CompletableFuture references [_ ^ReferenceParams params]
    (start :references
           (async-request params feature/references handler ::coercer/locations)))

  (^CompletableFuture completion [_ ^CompletionParams params]
    (start :completion
           (async-request params feature/completion handler ::coercer/completion-items (fn [items]
                                                                                         (format "total items: %s" (count items))))))

  (^CompletableFuture resolveCompletionItem [_ ^CompletionItem item]
    (start :resolveCompletionItem
           (async-request item feature/completion-resolve-item handler ::coercer/completion-item)))

  (^CompletableFuture prepareRename [_ ^PrepareRenameParams params]
    (start :prepare-rename
           (async-request params feature/prepare-rename handler ::coercer/prepare-rename-or-error)))

  (^CompletableFuture rename [_ ^RenameParams params]
    (start :rename
           (async-request params feature/rename handler ::coercer/workspace-edit-or-error)))

  (^CompletableFuture hover [_ ^HoverParams params]
    (start :hover
           (async-request params feature/hover handler ::coercer/hover)))

  (^CompletableFuture signatureHelp [_ ^SignatureHelpParams params]
    (start :signatureHelp
           (async-request params feature/signature-help handler ::coercer/signature-help)))

  (^CompletableFuture formatting [_ ^DocumentFormattingParams params]
    (start :formatting
           (async-request params feature/formatting handler ::coercer/edits)))

  (^CompletableFuture rangeFormatting [_this ^DocumentRangeFormattingParams params]
    (start :rangeFormatting
           (end
             (let [result (when (compare-and-set! formatting false true)
                            (try
                              (let [doc-id (coercer/document->uri (.getTextDocument params))
                                    range (.getRange params)
                                    start (.getStart range)
                                    end (.getEnd range)]
                                (coercer/conform-or-log ::coercer/edits (#'feature/range-formatting
                                                                         handler
                                                                         doc-id
                                                                         {:row (inc (.getLine start))
                                                                          :col (inc (.getCharacter start))
                                                                          :end-row (inc (.getLine end))
                                                                          :end-col (inc (.getCharacter end))})))
                              (catch Exception e
                                (log/error e))
                              (finally
                                (reset! formatting false))))]
               (CompletableFuture/completedFuture
                 result)))))

  (^CompletableFuture codeAction [_ ^CodeActionParams params]
    (start :codeAction
           (async-request params feature/code-actions handler ::coercer/code-actions)))

  (^CompletableFuture codeLens [_ ^CodeLensParams params]
    (start :codeLens
           (async-request params feature/code-lens handler ::coercer/code-lenses)))

  (^CompletableFuture resolveCodeLens [_ ^CodeLens params]
    (start :resolveCodeLens
           (async-request params feature/code-lens-resolve handler ::coercer/code-lens)))

  (^CompletableFuture definition [_ ^DefinitionParams params]
    (start :definition
           (async-request params feature/definition handler ::coercer/location)))

  (^CompletableFuture declaration [_ ^DeclarationParams params]
    (start :declaration
           (async-request params feature/declaration handler ::coercer/location)))

  (^CompletableFuture implementation [_ ^ImplementationParams params]
    (start :implementation
           (async-request params feature/implementation handler ::coercer/locations)))

  (^CompletableFuture documentSymbol [_ ^DocumentSymbolParams params]
    (start :documentSymbol
           (async-request params feature/document-symbol handler ::coercer/document-symbols)))

  (^CompletableFuture documentHighlight [_ ^DocumentHighlightParams params]
    (start :documentHighlight
           (async-request params feature/document-highlight handler ::coercer/document-highlights)))

  (^CompletableFuture semanticTokensFull [_ ^SemanticTokensParams params]
    (start :semanticTokensFull
           (async-request params feature/semantic-tokens-full handler ::coercer/semantic-tokens)))

  (^CompletableFuture semanticTokensRange [_ ^SemanticTokensRangeParams params]
    (start :semanticTokensRange
           (async-request params feature/semantic-tokens-range handler ::coercer/semantic-tokens)))

  (^CompletableFuture prepareCallHierarchy [_ ^CallHierarchyPrepareParams params]
    (start :prepareCallHierarchy
           (async-request params feature/prepare-call-hierarchy handler ::coercer/call-hierarchy-items)))

  (^CompletableFuture callHierarchyIncomingCalls [_ ^CallHierarchyIncomingCallsParams params]
    (start :callHierarchyIncomingCalls
           (async-request params feature/call-hierarchy-incoming handler ::coercer/call-hierarchy-incoming-calls)))

  (^CompletableFuture callHierarchyOutgoingCalls [_ ^CallHierarchyOutgoingCallsParams params]
    (start :callHierarchyOutgoingCalls
           (async-request params feature/call-hierarchy-outgoing handler ::coercer/call-hierarchy-outgoing-calls)))

  (^CompletableFuture linkedEditingRange [_ ^LinkedEditingRangeParams params]
    (start :linkedEditingRange
           (async-request params feature/linked-editing-ranges handler ::coercer/linked-editing-ranges-or-error))))

(deftype LSPWorkspaceService [handler]
  WorkspaceService
  (^CompletableFuture executeCommand [_ ^ExecuteCommandParams params]
    (start :executeCommand
           (future
             (sync-notification params feature/execute-command handler)))
    (CompletableFuture/completedFuture 0))

  (^void didChangeConfiguration [_ ^DidChangeConfigurationParams params]
    (log/warn (coercer/java->clj params)))

  (^void didChangeWatchedFiles [_ ^DidChangeWatchedFilesParams params]
    (start :didChangeWatchedFiles
           (async-notification params feature/did-change-watched-files handler)))

  ;; TODO wait for lsp4j release
  #_(^void didDeleteFiles [_ ^DeleteFilesParams params]
                          (start :didSave
                                 (sync-handler params handler/did-delete-files habdler)))

  (^CompletableFuture symbol [_ ^WorkspaceSymbolParams params]
    (start :workspaceSymbol
           (async-request params feature/workspace-symbols handler ::coercer/workspace-symbols))))

(defn client-capabilities [^InitializeParams params]
  (some->> params
           .getCapabilities
           (coercer/conform-or-log ::coercer/client-capabilities)))

(defn start-parent-process-liveness-probe!
  [ppid server]
  (go-loop []
    (<! (timeout 5000))
    (if (shared/process-alive? ppid)
      (recur)
      (do
        (log/info "Parent process" ppid "is not running - exiting server")
        (.exit ^LanguageServer server)))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(deftype LSPServer [^ILSPFeature handler db capabilities client-settings]
  LanguageServer
  (^CompletableFuture initialize [this ^InitializeParams params]
    (start :initialize
           (end
             (do
               (log/info "Initializing...")
               (feature/initialize handler (.getRootUri params)
                                   (client-capabilities params)
                                   (-> params
                                       coercer/java->clj
                                       client-settings)
                                   (some-> (.getWorkDoneToken params) .get str))
               (when-let [parent-process-id (.getProcessId params)]
                 (start-parent-process-liveness-probe! parent-process-id this))
               (let [capabilities (capabilities db)]
                 (CompletableFuture/completedFuture
                   (InitializeResult. (coercer/conform-or-log ::coercer/server-capabilities capabilities))))))))

  (^void initialized [_ ^InitializedParams _params]
    (start :initialized
           (end
             (do
               (log/info "Initialized!")
               (producer/register-capability
                 (:producer @db)
                 (RegistrationParams.
                   [(Registration. "id" "workspace/didChangeWatchedFiles"
                                   (DidChangeWatchedFilesRegistrationOptions.
                                     [(FileSystemWatcher. "**/*.{clj,cljs,cljc,edn}")]))]))))))

  (^CompletableFuture shutdown [_]
    (log/info "Shutting down")
    (reset! db {:documents {}})
    (CompletableFuture/completedFuture
      {:result nil}))
  (exit [_]
    (log/info "Exitting...")
    (shutdown-agents)
    (System/exit 0))
  (getTextDocumentService [_]
    (LSPTextDocumentService. handler))
  (getWorkspaceService [_]
    (LSPWorkspaceService. handler)))

(defn tee-system-in [^java.io.InputStream system-in]
  (let [buffer-size 1024
        os (java.io.PipedOutputStream.)
        is (java.io.PipedInputStream. os)]
    (thread
      (try
        (let [buffer (byte-array buffer-size)]
          (loop [chs (.read system-in buffer 0 buffer-size)]
            (when (pos? chs)
              (log/warn "FROM STDIN" chs (String. (java.util.Arrays/copyOfRange buffer 0 chs)))
              (.write os buffer 0 chs)
              (recur (.read system-in buffer 0 buffer-size)))))
        (catch Exception e
          (log/error e "in thread"))))
    is))

(defn tee-system-out [^java.io.OutputStream system-out]
  (let [buffer-size 1024
        is (java.io.PipedInputStream.)
        os (java.io.PipedOutputStream. is)]
    (thread
      (try
        (let [buffer (byte-array buffer-size)]
          (loop [chs (.read is buffer 0 buffer-size)]
            (when (pos? chs)
              (log/warn "FROM STDOUT" chs (String. (java.util.Arrays/copyOfRange buffer 0 chs)))
              (.write system-out buffer)
              (recur (.read is buffer 0 buffer-size)))))
        (catch Exception e
          (log/error e "in thread"))))
    os))

(defrecord LSPProducer [^LanguageClient client db]
  producer/IProducer

  (publish-diagnostic [_this diagnostic]
    (->> diagnostic
         (coercer/conform-or-log ::coercer/publish-diagnostics-params)
         (.publishDiagnostics client)))

  (refresh-code-lens [_this]
    (when-let [code-lens-capability ^CodeLensWorkspaceCapabilities (get-in @db [:client-capabilities :workspace :code-lens])]
      (when (.getRefreshSupport code-lens-capability)
        (.refreshCodeLenses client))))

  (publish-workspace-edit [_this edit]
    (->> edit
         (coercer/conform-or-log ::coercer/workspace-edit-or-error)
         ApplyWorkspaceEditParams.
         (.applyEdit client)))

  (show-document-request [_this document-request]
    (log/info "Requesting to show on editor the document" document-request)
    (when (.getShowDocument ^WindowClientCapabilities (get-in @db [:client-capabilities :window]))
      (->> (update document-request :range #(or (some-> % shared/->range)
                                                (shared/full-file-range)))
           (coercer/conform-or-log ::coercer/show-document-request)
           (.showDocument client))))

  (publish-progress [_this percentage message progress-token]
    (let [progress (case (int percentage)
                     0 {:kind :begin
                        :title message
                        :percentage percentage}
                     100 {:kind :end
                          :message message
                          :percentage 100}
                     {:kind :report
                      :message message
                      :percentage percentage})]
      (->> {:token (or progress-token "clojure-lsp")
            :value progress}
           (coercer/conform-or-log ::coercer/notify-progress)
           (.notifyProgress client))))

  (show-message-request [_this message type actions]
    (let [result (->> {:type type
                       :message message
                       :actions actions}
                      (coercer/conform-or-log ::coercer/show-message-request)
                      (.showMessageRequest client)
                      .get)]
      (.getTitle ^MessageActionItem result)))

  (show-message [_this message type extra]
    (let [message-content {:message message
                           :type type
                           :extra extra}]
      (log/info message-content)
      (->> message-content
           (coercer/conform-or-log ::coercer/show-message)
           (.showMessage client))))

  (register-capability [_this capability]
    (.registerCapability
      client
      capability)))
