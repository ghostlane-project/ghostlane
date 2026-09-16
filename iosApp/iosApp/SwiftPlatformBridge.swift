import AVFoundation
import Foundation
import SwiftUI
import SharedUI
import UniformTypeIdentifiers
import UIKit

final class SwiftPlatformBridge: NSObject, @preconcurrency IosPlatformBridge, UIDocumentPickerDelegate {
    weak var presenter: UIViewController?

    private enum DocumentAction {
        case importConfig(IosTextCallback)
        case exportLogs(IosMessageCallback, URL)
    }

    private var documentAction: DocumentAction?

    func readClipboard() -> String? {
        UIPasteboard.general.string
    }

    func writeClipboard(text: String) {
        DispatchQueue.main.async {
            UIPasteboard.general.string = text
        }
    }

    func pickConfigText(callback: IosTextCallback) {
        DispatchQueue.main.async {
            self.documentAction = .importConfig(callback)
            let picker = UIDocumentPickerViewController(
                forOpeningContentTypes: [.plainText, .json, .data],
                asCopy: true
            )
            picker.delegate = self
            self.present(picker)
        }
    }

    func scanQrCode(callback: IosTextCallback) {
        let answer = SendableTextCallback(callback: callback)
        DispatchQueue.main.async {
            // Asked for explicitly rather than left to the capture session: a
            // session started without permission simply shows black, which reads
            // as a broken camera rather than as a decision the user has to make.
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                self.presentScanner(answer)
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    DispatchQueue.main.async {
                        if granted {
                            self.presentScanner(answer)
                        } else {
                            answer.callback.onError(message: "Camera access denied")
                        }
                    }
                }
            case .denied, .restricted:
                answer.callback.onError(
                    message: "Camera access is off for Ghostlane. Settings → Ghostlane → Camera."
                )
            @unknown default:
                answer.callback.onError(message: "Camera unavailable")
            }
        }
    }

    private func presentScanner(_ answer: SendableTextCallback) {
        let scanner = QrScannerViewController { [weak self] result in
            self?.topPresenter()?.dismiss(animated: true)
            switch result {
            case .success(let text): answer.callback.onSuccess(text: text)
            case .failure(let message): answer.callback.onError(message: message)
            }
        }
        scanner.modalPresentationStyle = .fullScreen
        self.present(scanner)
    }

    func shareText(title: String, text: String) {
        DispatchQueue.main.async {
            let controller = UIActivityViewController(activityItems: [text], applicationActivities: nil)
            controller.title = title
            self.present(controller)
        }
    }

    func saveLogs(defaultName: String, content: String, callback: IosMessageCallback) {
        DispatchQueue.main.async {
            do {
                let url = try self.writeTemporaryFile(defaultName: defaultName, content: content)
                self.documentAction = .exportLogs(callback, url)
                let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
                picker.delegate = self
                self.present(picker)
            } catch {
                callback.onError(message: error.localizedDescription)
            }
        }
    }

    func shareLogs(defaultName: String, content: String, callback: IosMessageCallback) {
        DispatchQueue.main.async {
            do {
                let url = try self.writeTemporaryFile(defaultName: defaultName, content: content)
                let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
                controller.completionWithItemsHandler = { _, completed, _, error in
                    if let error {
                        callback.onError(message: error.localizedDescription)
                    } else if completed {
                        callback.onSuccess(message: "Logs shared")
                    } else {
                        callback.onError(message: "Log sharing cancelled")
                    }
                }
                self.present(controller)
            } catch {
                callback.onError(message: error.localizedDescription)
            }
        }
    }

    func openUrl(url: String) {
        guard let target = URL(string: url) else { return }
        DispatchQueue.main.async {
            UIApplication.shared.open(target)
        }
    }

    func showMessage(message: String) {
        DispatchQueue.main.async {
            guard let presenter = self.topPresenter() else { return }
            let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            presenter.present(alert, animated: true)
        }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let action = documentAction else { return }
        documentAction = nil

        switch action {
        case .importConfig(let callback):
            guard let url = urls.first else {
                callback.onError(message: "No file selected")
                return
            }
            let didAccess = url.startAccessingSecurityScopedResource()
            defer {
                if didAccess {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            do {
                callback.onSuccess(text: try String(contentsOf: url, encoding: .utf8))
            } catch {
                callback.onError(message: error.localizedDescription)
            }

        case .exportLogs(let callback, let tempUrl):
            callback.onSuccess(message: "Logs saved")
            try? FileManager.default.removeItem(at: tempUrl)
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        guard let action = documentAction else { return }
        documentAction = nil

        switch action {
        case .importConfig(let callback):
            callback.onError(message: "File import cancelled")
        case .exportLogs(let callback, let tempUrl):
            callback.onError(message: "Log export cancelled")
            try? FileManager.default.removeItem(at: tempUrl)
        }
    }

    private func present(_ controller: UIViewController) {
        guard let presenter = topPresenter() else { return }
        if let popover = controller.popoverPresentationController {
            popover.sourceView = presenter.view
            popover.sourceRect = CGRect(
                x: presenter.view.bounds.midX,
                y: presenter.view.bounds.midY,
                width: 1,
                height: 1
            )
            popover.permittedArrowDirections = []
        }
        presenter.present(controller, animated: true)
    }

    private func topPresenter() -> UIViewController? {
        var top = presenter ?? UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }?
            .rootViewController

        while let presented = top?.presentedViewController {
            top = presented
        }

        return top
    }

    private func writeTemporaryFile(defaultName: String, content: String) throws -> URL {
        let sanitized = (defaultName.isEmpty ? "ghostlane-logs.txt" : defaultName)
            .replacingOccurrences(of: "/", with: "_")
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(UUID().uuidString)-\(sanitized)")
        try content.write(to: url, atomically: true, encoding: .utf8)
        return url
    }
}

/// An `AVCaptureSession` on its way to a background queue.
///
/// `nonisolated(unsafe)` on the property was the wrong tool: that says where the
/// property may be *read from*, and the complaint is about carrying a
/// non-Sendable value across a `@Sendable` boundary. `startRunning` and
/// `stopRunning` are the two calls Apple documents as safe off the main thread —
/// indeed startRunning must not be on it, since it blocks until the camera is
/// configured — so the box is narrow and true rather than a way to quiet the
/// compiler.
private struct SendableSession: @unchecked Sendable {
    let value: AVCaptureSession
}

/// Kotlin's callback in terms Swift concurrency accepts. Objects from
/// Kotlin/Native carry no Sendable annotation but are safe to call from any
/// thread under its memory model.
private struct SendableTextCallback: @unchecked Sendable {
    let callback: IosTextCallback
}

/// Reads one QR code and gets out of the way.
///
/// Lives in this file rather than its own: the app target lists its sources by
/// name in project.pbxproj — only the PacketTunnel folder is a synchronized
/// group — so a new .swift here would not be compiled until someone added it in
/// Xcode, and would fail as a mysteriously missing symbol.
/// `@preconcurrency` on the delegate conformance, the same way this file already
/// conforms to `IosPlatformBridge`: `UIViewController` is `@MainActor`, the
/// capture delegate protocol is not isolated at all, and Swift 6 calls that
/// crossing a data race. It is not one here — the output is given `.main` as its
/// callback queue below — but the compiler cannot see that, and the annotation
/// is how you say so.
final class QrScannerViewController: UIViewController,
                                     @preconcurrency AVCaptureMetadataOutputObjectsDelegate {

    enum Outcome {
        case success(String)
        case failure(String)
    }

    private let finished: (Outcome) -> Void
    /// Handed to a background queue in `viewWillAppear`, because `startRunning`
    /// blocks until the camera is configured and would freeze the presentation
    /// animation on the main thread. `AVCaptureSession` is not Sendable, and
    /// start/stop are the two calls Apple documents as safe off the main thread —
    /// so the opt-out is narrow and true rather than a way to quiet the compiler.
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    /// A capture session keeps delivering after the first match; without this
    /// the callback fires once per frame and Kotlin sees a burst of imports.
    private var answered = false

    init(finished: @escaping (Outcome) -> Void) {
        self.finished = finished
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not from a nib") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            answer(.failure("No camera available"))
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            answer(.failure("Camera cannot read QR codes"))
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        // Set only after the output is attached — assigning it before throws.
        output.metadataObjectTypes = [.qr]

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        preview = layer

        let hint = UILabel()
        hint.text = "Point the camera at a server-list QR code"
        hint.textColor = .white
        hint.textAlignment = .center
        hint.numberOfLines = 0
        hint.font = .preferredFont(forTextStyle: .callout)
        hint.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hint)

        let cancel = UIButton(type: .system)
        cancel.setTitle("Cancel", for: .normal)
        cancel.setTitleColor(.white, for: .normal)
        cancel.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        cancel.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        cancel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(cancel)

        NSLayoutConstraint.activate([
            hint.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            hint.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            hint.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 24),
            cancel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            cancel.bottomAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -32
            )
        ])
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        guard !session.isRunning else { return }
        // Never on the main thread: startRunning blocks until the camera is
        // configured, which freezes the presentation animation.
        let box = SendableSession(value: session)
        DispatchQueue.global(qos: .userInitiated).async { box.value.startRunning() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        guard session.isRunning else { return }
        let box = SendableSession(value: session)
        DispatchQueue.global(qos: .userInitiated).async { box.value.stopRunning() }
    }

    @objc private func cancelTapped() {
        answer(.failure("Scan cancelled"))
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              object.type == .qr,
              let text = object.stringValue,
              !text.isEmpty else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        answer(.success(text))
    }

    private func answer(_ outcome: Outcome) {
        guard !answered else { return }
        answered = true
        finished(outcome)
    }
}
