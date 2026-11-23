import UIKit
import AVFoundation
import Vision
import QuartzCore
import Shared

/// One session per visible scanner. Vision reports both detected and empty frames.
final class TicketCameraController: UIViewController, AVCaptureVideoDataOutputSampleBufferDelegate {
    private let session = AVCaptureSession()
    private let captureQueue = DispatchQueue(label: "biletflow.camera")
    private let framesQueue = DispatchQueue(label: "biletflow.qr.frames")
    private var preview: AVCaptureVideoPreviewLayer?
    private var configured = false
    private var stopped = false // accessed only on main
    private var delivering = false // accessed only on main
    private var lastFrame: CFTimeInterval = 0
    private let onFrame: ([String]) -> Void
    private let onState: (Int32) -> Void
    private var errorObserver: NSObjectProtocol?

    init(onFrame: @escaping ([String]) -> Void, onState: @escaping (Int32) -> Void) {
        self.onFrame = onFrame; self.onState = onState
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unsupported") }
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = .black
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill; view.layer.addSublayer(layer); preview = layer
        errorObserver = NotificationCenter.default.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: .main) { [weak self] _ in
            self?.cameraFailed()
        }
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews(); preview?.frame = view.bounds
        let angle: CGFloat
        switch view.window?.windowScene?.interfaceOrientation {
        case .landscapeLeft: angle = 0
        case .landscapeRight: angle = 180
        case .portraitUpsideDown: angle = 270
        default: angle = 90
        }
        if let connection = preview?.connection, connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
    }
    func requestAccess() {
        guard !stopped else { return }
        guard AVCaptureDevice.default(for: .video) != nil else { onState(3); return }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: start()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] allowed in
                DispatchQueue.main.async { guard let self, !self.stopped else { return }; allowed ? self.start() : self.onState(2) }
            }
        case .denied, .restricted: onState(2)
        @unknown default: onState(4)
        }
    }
    private func start() {
        loadViewIfNeeded()
        delivering = true
        onState(0)
        captureQueue.async { [weak self] in
            guard let self else { return }
            do {
                if !self.configured {
                    guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) ?? AVCaptureDevice.default(for: .video) else {
                        DispatchQueue.main.async { if !self.stopped { self.onState(3) } }; return
                    }
                    let input = try AVCaptureDeviceInput(device: camera)
                    let output = AVCaptureVideoDataOutput()
                    output.alwaysDiscardsLateVideoFrames = true
                    output.setSampleBufferDelegate(self, queue: self.framesQueue)
                    self.session.beginConfiguration()
                    if self.session.canSetSessionPreset(.hd1280x720) { self.session.sessionPreset = .hd1280x720 }
                    guard self.session.canAddInput(input), self.session.canAddOutput(output) else {
                        self.session.commitConfiguration(); throw NSError(domain: "Camera", code: 1)
                    }
                    self.session.addInput(input); self.session.addOutput(output)
                    self.session.commitConfiguration(); self.configured = true
                }
                self.session.startRunning()
            } catch { DispatchQueue.main.async { self.cameraFailed() } }
        }
    }
    private func cameraFailed() {
        guard !stopped else { return }
        delivering = false; onState(4)
        captureQueue.async { [self] in self.session.stopRunning() }
    }
    func stop() {
        guard !stopped else { return }; stopped = true; delivering = false
        if let observer = errorObserver { NotificationCenter.default.removeObserver(observer) }; errorObserver = nil
        captureQueue.async { [self] in
            self.session.stopRunning()
            self.session.beginConfiguration()
            self.session.outputs.forEach { output in
                (output as? AVCaptureVideoDataOutput)?.setSampleBufferDelegate(nil, queue: nil)
                self.session.removeOutput(output)
            }
            self.session.inputs.forEach { self.session.removeInput($0) }
            self.session.commitConfiguration()
        }
    }
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = CACurrentMediaTime()
        guard now - lastFrame >= 0.15, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }; lastFrame = now
        let request = VNDetectBarcodesRequest(); request.symbologies = [.qr]
        do {
            try VNImageRequestHandler(cvPixelBuffer: buffer, options: [:]).perform([request])
            let codes = (request.results ?? []).compactMap { $0.payloadStringValue }
            DispatchQueue.main.async { [weak self] in guard let self, !self.stopped, self.delivering else { return }; self.onFrame(codes) }
        } catch { DispatchQueue.main.async { [weak self] in self?.cameraFailed() } }
    }
    static func installBridge() {
        IosCameraBridge.shared.create = { frame, state in
            let controller = TicketCameraController(onFrame: { _ = frame($0) }, onState: { _ = state(KotlinInt(int: $0)) })
            return IosCameraHandle(controller: controller,
                requestAccess: { controller.requestAccess(); return KotlinUnit() },
                openSettings: { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }; return KotlinUnit() },
                stop: { controller.stop(); return KotlinUnit() })
        }
    }
}
