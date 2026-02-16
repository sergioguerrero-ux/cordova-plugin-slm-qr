import AVFoundation
import CoreImage
import UIKit

@objc(SLMQR) class SLMQR: CDVPlugin {

    private var scanCallbackId: String?
    private var scanMode: String = "qr" // "qr" or "barcode"

    // MARK: - scanQR

    @objc(scanQR:)
    func scanQR(command: CDVInvokedUrlCommand) {
        scanCallbackId = command.callbackId
        scanMode = "qr"
        let options = command.argument(at: 0) as? [String: Any] ?? [:]
        openScanner(options: options)
    }

    // MARK: - scanBarcode

    @objc(scanBarcode:)
    func scanBarcode(command: CDVInvokedUrlCommand) {
        scanCallbackId = command.callbackId
        scanMode = "barcode"
        let options = command.argument(at: 0) as? [String: Any] ?? [:]
        openScanner(options: options)
    }

    // MARK: - generateQR

    @objc(generateQR:)
    func generateQR(command: CDVInvokedUrlCommand) {
        let data = command.argument(at: 0) as? String ?? ""
        let options = command.argument(at: 1) as? [String: Any] ?? [:]

        let size = options["size"] as? Int ?? 300
        let colorHex = options["color"] as? String ?? "#000000"
        let bgColorHex = options["backgroundColor"] as? String ?? "#FFFFFF"
        let logoBase64 = options["logo"] as? String
        let errorCorrection = options["errorCorrection"] as? String ?? "M"

        DispatchQueue.global(qos: .userInitiated).async {
            guard let qrData = data.data(using: .utf8) else {
                let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "No se pudo codificar el texto")
                self.commandDelegate.send(result, callbackId: command.callbackId)
                return
            }

            guard let filter = CIFilter(name: "CIQRCodeGenerator") else {
                let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "CIQRCodeGenerator no disponible")
                self.commandDelegate.send(result, callbackId: command.callbackId)
                return
            }

            filter.setValue(qrData, forKey: "inputMessage")
            filter.setValue(errorCorrection, forKey: "inputCorrectionLevel")

            guard let ciImage = filter.outputImage else {
                let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "No se pudo generar el QR")
                self.commandDelegate.send(result, callbackId: command.callbackId)
                return
            }

            let scaleX = CGFloat(size) / ciImage.extent.size.width
            let scaleY = CGFloat(size) / ciImage.extent.size.height
            let scaledImage = ciImage.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))

            let context = CIContext()
            guard let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) else {
                let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "No se pudo renderizar el QR")
                self.commandDelegate.send(result, callbackId: command.callbackId)
                return
            }

            var uiImage = UIImage(cgImage: cgImage)

            // Apply colors
            if colorHex != "#000000" || bgColorHex != "#FFFFFF" {
                uiImage = self.colorizeQR(image: uiImage, foreground: self.colorFromHex(colorHex), background: self.colorFromHex(bgColorHex))
            }

            // Add logo if provided
            if let logoB64 = logoBase64,
               let logoData = Data(base64Encoded: logoB64),
               let logoImage = UIImage(data: logoData) {
                uiImage = self.addLogo(to: uiImage, logo: logoImage)
            }

            guard let pngData = uiImage.pngData() else {
                let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "No se pudo convertir a PNG")
                self.commandDelegate.send(result, callbackId: command.callbackId)
                return
            }

            let base64 = pngData.base64EncodedString()
            let info: [String: Any] = [
                "base64Image": base64,
                "format": "png"
            ]
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: info)
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
    }

    // MARK: - Scanner

    private func openScanner(options: [String: Any]) {
        let template = options["template"] as? String ?? "simple"
        let showFlashlight = options["flashlight"] as? Bool ?? true
        let shouldVibrate = options["vibrate"] as? Bool ?? true
        let cameraPosition = options["camera"] as? String ?? "back"
        let title = options["title"] as? String ?? (scanMode == "qr" ? "Escanea el codigo QR" : "Escanea el codigo de barras")

        DispatchQueue.main.async {
            let scannerVC = SLMQRScannerViewController()
            scannerVC.template = template
            scannerVC.showFlashlight = showFlashlight
            scannerVC.shouldVibrate = shouldVibrate
            scannerVC.useFrontCamera = cameraPosition == "front"
            scannerVC.titleText = title
            scannerVC.scanMode = self.scanMode
            scannerVC.modalPresentationStyle = .fullScreen

            scannerVC.onScanResult = { [weak self] result in
                guard let self = self, let callbackId = self.scanCallbackId else { return }
                let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: result)
                self.commandDelegate.send(pluginResult, callbackId: callbackId)
                self.scanCallbackId = nil
            }

            scannerVC.onCancel = { [weak self] in
                guard let self = self, let callbackId = self.scanCallbackId else { return }
                let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Escaneo cancelado por el usuario")
                self.commandDelegate.send(pluginResult, callbackId: callbackId)
                self.scanCallbackId = nil
            }

            self.viewController.present(scannerVC, animated: true)
        }
    }

    // MARK: - Helpers

    private func colorFromHex(_ hex: String) -> UIColor {
        var hexSanitized = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        hexSanitized = hexSanitized.replacingOccurrences(of: "#", with: "")

        var rgb: UInt64 = 0
        Scanner(string: hexSanitized).scanHexInt64(&rgb)

        return UIColor(
            red: CGFloat((rgb & 0xFF0000) >> 16) / 255.0,
            green: CGFloat((rgb & 0x00FF00) >> 8) / 255.0,
            blue: CGFloat(rgb & 0x0000FF) / 255.0,
            alpha: 1.0
        )
    }

    private func colorizeQR(image: UIImage, foreground: UIColor, background: UIColor) -> UIImage {
        let size = image.size
        UIGraphicsBeginImageContextWithOptions(size, false, 0)
        guard let context = UIGraphicsGetCurrentContext(), let cgImage = image.cgImage else { return image }

        context.setFillColor(background.cgColor)
        context.fill(CGRect(origin: .zero, size: size))

        // Draw QR with foreground color using masking
        context.clip(to: CGRect(origin: .zero, size: size), mask: cgImage)
        context.setFillColor(foreground.cgColor)
        context.fill(CGRect(origin: .zero, size: size))

        let result = UIGraphicsGetImageFromCurrentImageContext() ?? image
        UIGraphicsEndImageContext()
        return result
    }

    private func addLogo(to qrImage: UIImage, logo: UIImage) -> UIImage {
        let size = qrImage.size
        let logoSize = CGSize(width: size.width * 0.25, height: size.height * 0.25)
        let logoOrigin = CGPoint(x: (size.width - logoSize.width) / 2, y: (size.height - logoSize.height) / 2)

        UIGraphicsBeginImageContextWithOptions(size, false, 0)
        qrImage.draw(in: CGRect(origin: .zero, size: size))

        // White background for logo
        let padding: CGFloat = 4
        let bgRect = CGRect(
            x: logoOrigin.x - padding,
            y: logoOrigin.y - padding,
            width: logoSize.width + padding * 2,
            height: logoSize.height + padding * 2
        )
        UIColor.white.setFill()
        UIBezierPath(roundedRect: bgRect, cornerRadius: 4).fill()

        logo.draw(in: CGRect(origin: logoOrigin, size: logoSize))

        let result = UIGraphicsGetImageFromCurrentImageContext() ?? qrImage
        UIGraphicsEndImageContext()
        return result
    }
}

// MARK: - Scanner ViewController

class SLMQRScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {

    var template: String = "simple"
    var showFlashlight: Bool = true
    var shouldVibrate: Bool = true
    var useFrontCamera: Bool = false
    var titleText: String = "Escanea el codigo QR"
    var scanMode: String = "qr"

    var onScanResult: (([String: Any]) -> Void)?
    var onCancel: (() -> Void)?

    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var hasDetected = false

    // Overlay views
    private var overlayView: UIView?
    private var cornerViews: [UIView] = []
    private var pulseAnimation: CABasicAnimation?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupCamera()
        setupOverlay()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if let session = captureSession, !session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async {
                session.startRunning()
            }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        captureSession?.stopRunning()
    }

    override var prefersStatusBarHidden: Bool { return true }

    // MARK: - Camera Setup

    private func setupCamera() {
        let session = AVCaptureSession()
        session.sessionPreset = .high

        let position: AVCaptureDevice.Position = useFrontCamera ? .front : .back
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) else {
            dismissWithError("Camara no disponible")
            return
        }

        guard let input = try? AVCaptureDeviceInput(device: device) else {
            dismissWithError("No se pudo acceder a la camara")
            return
        }

        if session.canAddInput(input) {
            session.addInput(input)
        }

        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)

            if scanMode == "qr" {
                output.metadataObjectTypes = [.qr]
            } else {
                output.metadataObjectTypes = [.ean8, .ean13, .upce, .code39, .code93, .code128, .pdf417, .aztec, .itf14, .dataMatrix, .interleaved2of5]
            }
        }

        let previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.frame = view.bounds
        view.layer.addSublayer(previewLayer)

        self.captureSession = session
        self.previewLayer = previewLayer

        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
    }

    // MARK: - Overlay Setup

    private func setupOverlay() {
        switch template {
        case "credential":
            setupCredentialOverlay()
        case "simple":
            setupSimpleOverlay()
        case "fullscreen":
            setupFullscreenOverlay()
        default:
            setupSimpleOverlay()
        }
    }

    private func setupCredentialOverlay() {
        let overlay = UIView(frame: view.bounds)
        overlay.backgroundColor = .clear
        view.addSubview(overlay)
        self.overlayView = overlay

        // Dark mask with card-shaped cutout
        let maskLayer = CAShapeLayer()
        let fullPath = UIBezierPath(rect: view.bounds)

        // Credit card ratio 1.586:1
        let cardWidth = view.bounds.width * 0.85
        let cardHeight = cardWidth / 1.586
        let cardX = (view.bounds.width - cardWidth) / 2
        let cardY = (view.bounds.height - cardHeight) / 2
        let cardRect = CGRect(x: cardX, y: cardY, width: cardWidth, height: cardHeight)
        let cardPath = UIBezierPath(roundedRect: cardRect, cornerRadius: 12)

        fullPath.append(cardPath)
        fullPath.usesEvenOddFillRule = true

        maskLayer.path = fullPath.cgPath
        maskLayer.fillRule = .evenOdd
        maskLayer.fillColor = UIColor.black.withAlphaComponent(0.7).cgColor

        let darkView = UIView(frame: view.bounds)
        darkView.layer.addSublayer(maskLayer)
        overlay.addSubview(darkView)

        // Card border
        let borderView = UIView(frame: cardRect)
        borderView.layer.borderColor = UIColor.white.withAlphaComponent(0.5).cgColor
        borderView.layer.borderWidth = 1.5
        borderView.layer.cornerRadius = 12
        overlay.addSubview(borderView)

        // QR scan zone inside card (right side, where credentials typically place QR)
        let qrSize = cardHeight * 0.65
        let qrX = cardX + cardWidth - qrSize - cardWidth * 0.08
        let qrY = cardY + (cardHeight - qrSize) / 2
        let qrRect = CGRect(x: qrX, y: qrY, width: qrSize, height: qrSize)

        addAnimatedCorners(to: qrRect, parent: overlay)

        // Title label
        let titleLabel = UILabel()
        titleLabel.text = titleText
        titleLabel.textColor = .white
        titleLabel.font = UIFont.systemFont(ofSize: 18, weight: .semibold)
        titleLabel.textAlignment = .center
        titleLabel.frame = CGRect(x: 20, y: cardY - 60, width: view.bounds.width - 40, height: 40)
        overlay.addSubview(titleLabel)

        // Cancel button
        let cancelBtn = UIButton(type: .system)
        cancelBtn.setTitle("Cancelar", for: .normal)
        cancelBtn.setTitleColor(.white, for: .normal)
        cancelBtn.titleLabel?.font = UIFont.systemFont(ofSize: 17, weight: .medium)
        cancelBtn.frame = CGRect(x: 20, y: view.bounds.height - 100, width: 100, height: 44)
        cancelBtn.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        overlay.addSubview(cancelBtn)

        // Flashlight button
        if showFlashlight {
            let flashBtn = UIButton(type: .system)
            flashBtn.setTitle("Flash", for: .normal)
            flashBtn.setTitleColor(.white, for: .normal)
            flashBtn.titleLabel?.font = UIFont.systemFont(ofSize: 17, weight: .medium)
            flashBtn.frame = CGRect(x: view.bounds.width - 120, y: view.bounds.height - 100, width: 100, height: 44)
            flashBtn.addTarget(self, action: #selector(toggleFlash), for: .touchUpInside)
            overlay.addSubview(flashBtn)
        }
    }

    private func setupSimpleOverlay() {
        let overlay = UIView(frame: view.bounds)
        overlay.backgroundColor = .clear
        view.addSubview(overlay)
        self.overlayView = overlay

        // Dark mask with centered square cutout
        let maskLayer = CAShapeLayer()
        let fullPath = UIBezierPath(rect: view.bounds)

        let scanSize = min(view.bounds.width, view.bounds.height) * 0.65
        let scanX = (view.bounds.width - scanSize) / 2
        let scanY = (view.bounds.height - scanSize) / 2
        let scanRect = CGRect(x: scanX, y: scanY, width: scanSize, height: scanSize)
        let scanPath = UIBezierPath(roundedRect: scanRect, cornerRadius: 16)

        fullPath.append(scanPath)
        fullPath.usesEvenOddFillRule = true

        maskLayer.path = fullPath.cgPath
        maskLayer.fillRule = .evenOdd
        maskLayer.fillColor = UIColor.black.withAlphaComponent(0.6).cgColor

        let darkView = UIView(frame: view.bounds)
        darkView.layer.addSublayer(maskLayer)
        overlay.addSubview(darkView)

        addAnimatedCorners(to: scanRect, parent: overlay)

        // Title
        let titleLabel = UILabel()
        titleLabel.text = titleText
        titleLabel.textColor = .white
        titleLabel.font = UIFont.systemFont(ofSize: 18, weight: .semibold)
        titleLabel.textAlignment = .center
        titleLabel.frame = CGRect(x: 20, y: scanY - 60, width: view.bounds.width - 40, height: 40)
        overlay.addSubview(titleLabel)

        // Cancel
        let cancelBtn = UIButton(type: .system)
        cancelBtn.setTitle("Cancelar", for: .normal)
        cancelBtn.setTitleColor(.white, for: .normal)
        cancelBtn.titleLabel?.font = UIFont.systemFont(ofSize: 17, weight: .medium)
        cancelBtn.frame = CGRect(x: (view.bounds.width - 100) / 2, y: view.bounds.height - 100, width: 100, height: 44)
        cancelBtn.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        overlay.addSubview(cancelBtn)

        // Flashlight
        if showFlashlight {
            let flashBtn = UIButton(type: .system)
            flashBtn.setTitle("Flash", for: .normal)
            flashBtn.setTitleColor(.white, for: .normal)
            flashBtn.titleLabel?.font = UIFont.systemFont(ofSize: 17, weight: .medium)
            flashBtn.frame = CGRect(x: view.bounds.width - 90, y: view.bounds.height - 100, width: 70, height: 44)
            flashBtn.addTarget(self, action: #selector(toggleFlash), for: .touchUpInside)
            overlay.addSubview(flashBtn)
        }
    }

    private func setupFullscreenOverlay() {
        let overlay = UIView(frame: view.bounds)
        overlay.backgroundColor = .clear
        view.addSubview(overlay)
        self.overlayView = overlay

        // Cancel button only
        let cancelBtn = UIButton(type: .system)
        cancelBtn.setTitle("Cancelar", for: .normal)
        cancelBtn.setTitleColor(.white, for: .normal)
        cancelBtn.titleLabel?.font = UIFont.systemFont(ofSize: 17, weight: .medium)
        cancelBtn.frame = CGRect(x: 20, y: view.bounds.height - 100, width: 100, height: 44)
        cancelBtn.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        overlay.addSubview(cancelBtn)

        if showFlashlight {
            let flashBtn = UIButton(type: .system)
            flashBtn.setTitle("Flash", for: .normal)
            flashBtn.setTitleColor(.white, for: .normal)
            flashBtn.titleLabel?.font = UIFont.systemFont(ofSize: 17, weight: .medium)
            flashBtn.frame = CGRect(x: view.bounds.width - 90, y: view.bounds.height - 100, width: 70, height: 44)
            flashBtn.addTarget(self, action: #selector(toggleFlash), for: .touchUpInside)
            overlay.addSubview(flashBtn)
        }
    }

    // MARK: - Animated Corners

    private func addAnimatedCorners(to rect: CGRect, parent: UIView) {
        let cornerLength: CGFloat = 24
        let cornerWidth: CGFloat = 3
        let color = UIColor(red: 0.0, green: 0.75, blue: 1.0, alpha: 1.0) // Cyan accent

        let positions: [(CGFloat, CGFloat, Bool, Bool)] = [
            (rect.minX, rect.minY, false, false), // top-left
            (rect.maxX, rect.minY, true, false),   // top-right
            (rect.minX, rect.maxY, false, true),   // bottom-left
            (rect.maxX, rect.maxY, true, true)     // bottom-right
        ]

        for (x, y, flipX, flipY) in positions {
            let corner = UIView()
            corner.backgroundColor = .clear

            let hBar = UIView()
            hBar.backgroundColor = color
            hBar.layer.cornerRadius = cornerWidth / 2
            hBar.frame = CGRect(
                x: flipX ? -cornerLength : 0,
                y: flipY ? -cornerWidth : 0,
                width: cornerLength,
                height: cornerWidth
            )
            corner.addSubview(hBar)

            let vBar = UIView()
            vBar.backgroundColor = color
            vBar.layer.cornerRadius = cornerWidth / 2
            vBar.frame = CGRect(
                x: flipX ? -cornerWidth : 0,
                y: flipY ? -cornerLength : 0,
                width: cornerWidth,
                height: cornerLength
            )
            corner.addSubview(vBar)

            corner.frame = CGRect(x: x, y: y, width: 1, height: 1)
            parent.addSubview(corner)
            cornerViews.append(corner)
        }

        // Pulse animation
        startPulseAnimation()
    }

    private func startPulseAnimation() {
        let animation = CABasicAnimation(keyPath: "opacity")
        animation.fromValue = 1.0
        animation.toValue = 0.3
        animation.duration = 1.2
        animation.autoreverses = true
        animation.repeatCount = .infinity
        animation.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)

        for corner in cornerViews {
            corner.layer.add(animation, forKey: "pulse")
        }
    }

    // MARK: - AVCaptureMetadataOutputObjectsDelegate

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !hasDetected else { return }
        guard let metadataObject = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let stringValue = metadataObject.stringValue else { return }

        hasDetected = true

        // Haptic feedback
        if shouldVibrate {
            let generator = UIImpactFeedbackGenerator(style: .medium)
            generator.impactOccurred()
        }

        captureSession?.stopRunning()

        var format = "UNKNOWN"
        switch metadataObject.type {
        case .qr: format = "QR_CODE"
        case .ean8: format = "EAN_8"
        case .ean13: format = "EAN_13"
        case .upce: format = "UPC_E"
        case .code39: format = "CODE_39"
        case .code93: format = "CODE_93"
        case .code128: format = "CODE_128"
        case .pdf417: format = "PDF_417"
        case .aztec: format = "AZTEC"
        case .itf14: format = "ITF_14"
        case .dataMatrix: format = "DATA_MATRIX"
        case .interleaved2of5: format = "ITF"
        default: format = metadataObject.type.rawValue
        }

        var result: [String: Any] = [
            "text": stringValue,
            "format": format
        ]

        if scanMode == "qr" {
            result["template"] = template
            if let rawData = stringValue.data(using: .utf8) {
                result["rawBytes"] = rawData.base64EncodedString()
            }
        }

        dismiss(animated: true) { [weak self] in
            self?.onScanResult?(result)
        }
    }

    // MARK: - Actions

    @objc private func cancelTapped() {
        captureSession?.stopRunning()
        dismiss(animated: true) { [weak self] in
            self?.onCancel?()
        }
    }

    @objc private func toggleFlash() {
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else { return }
        try? device.lockForConfiguration()
        device.torchMode = device.torchMode == .on ? .off : .on
        device.unlockForConfiguration()
    }

    private func dismissWithError(_ message: String) {
        dismiss(animated: true) { [weak self] in
            self?.onCancel?()
        }
    }
}
