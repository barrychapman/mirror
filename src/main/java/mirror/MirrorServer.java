package mirror;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import io.grpc.stub.StreamObserver;
import mirror.MirrorGrpc.Mirror;

public class MirrorServer implements Mirror {

  private final Path root;
  private final FileAccess fs = new NativeFileAccess();

  public MirrorServer(Path root) {
    this.root = root;
  }

  @Override
  public StreamObserver<Update> connect(StreamObserver<Update> outgoingUpdates) {
    BlockingQueue<Update> queue = new ArrayBlockingQueue<>(10_000);
    try {
      WatchService watchService = FileSystems.getDefault().newWatchService();
      FileWatcher r = new FileWatcher(watchService, root, queue);
      // throw away initial scan for now
      r.performInitialScan();
      List<Update> initial = new ArrayList<>();
      queue.drainTo(initial);
      r.startPolling();

      SyncLogic s = new SyncLogic(root, queue, outgoingUpdates, fs);
      s.startPolling();

      // make an observable for when the client sends in new updates
      StreamObserver<Update> incomingUpdates = new StreamObserver<Update>() {
        @Override
        public void onNext(Update value) {
          System.out.println("Received from client " + value);
          queue.add(value);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
          outgoingUpdates.onCompleted();
        }
      };
      return incomingUpdates;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

}
