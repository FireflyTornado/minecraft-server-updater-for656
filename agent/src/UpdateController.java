/**
 * Orchestrates the background update task and drives the {@link UpdateView}.
 *
 * Owns the {@link UpdateService} and the {@link UpdateView}. Starts the service
 * on a daemon worker thread, receives its {@link UpdateEvent}s as an
 * {@link UpdateListener}, marshals each onto the UI thread through a
 * {@link UiDispatcher} and translates it into an {@link UpdateView} call.
 * Completion and failure outcomes are forwarded to the {@link UpdateApplication}
 * for flow control (latch, delays, exit). Contains no Swing types, so the whole
 * flow is reusable for any UI toolkit.
 */
final class UpdateController implements UpdateListener {

    private final UpdateService service;
    private final UpdateView view;
    private final UiDispatcher dispatcher;
    private final UpdateApplication app;

    UpdateController(UpdateService service, UpdateView view, UiDispatcher dispatcher,
                     UpdateApplication app) {
        this.service = service;
        this.view = view;
        this.dispatcher = dispatcher;
        this.app = app;
    }

    /** Start the update on a background thread. Must run on the UI thread. */
    void start() {
        Thread worker = new Thread(() -> {
            try {
                service.run(this);
            } catch (Throwable t) {
                // run() catches recoverable exceptions itself and emits Failed,
                // so only Errors escape — surface them as an error.
                dispatcher.invoke(() -> app.onUpdateError(t));
            }
        }, "update-worker");
        worker.setDaemon(true);
        worker.start();
    }

    // ── UpdateListener (called on the worker thread) ───────────────

    @Override
    public void onUpdateEvent(UpdateEvent event) {
        dispatcher.invoke(() -> apply(event));
    }

    /** Translate one business event into view calls. Must run on the UI thread. */
    private void apply(UpdateEvent event) {
        switch (event.type) {
            case STATUS_CHANGED: {
                UpdateEvent.StatusChanged e = (UpdateEvent.StatusChanged) event;
                view.showStatus(e.status, e.indeterminate);
                break;
            }
            case OVERALL_PROGRESS_CHANGED:
                view.showOverallProgress(((UpdateEvent.OverallProgressChanged) event).percent);
                break;
            case DOWNLOAD_PROGRESS_CHANGED:
                view.showDownloadProgress(((UpdateEvent.DownloadProgressChanged) event).progress);
                break;
            case LOG_MESSAGE:
                view.showLog(((UpdateEvent.LogMessage) event).message);
                break;
            case SERVER_CHANGED: {
                UpdateEvent.ServerChanged e = (UpdateEvent.ServerChanged) event;
                view.showServer(e.serverUrls, e.currentServer);
                break;
            }
            case COMPLETED: {
                UpdateResult result = ((UpdateEvent.Completed) event).result;
                view.showCompleted(result);
                app.onUpdateFinished(result);
                break;
            }
            case FAILED: {
                UpdateEvent.Failed e = (UpdateEvent.Failed) event;
                view.showError(e.message, e.cause);
                app.onUpdateError(e.cause);
                break;
            }
        }
    }
}
