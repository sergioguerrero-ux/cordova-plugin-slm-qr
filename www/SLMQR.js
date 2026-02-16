var exec = require('cordova/exec');

var SLMQR = {

    /**
     * Abre la camara con overlay customizado para escanear codigos QR.
     * @param {Object} options - Opciones de escaneo
     *   {
     *     template: "credential"|"simple"|"fullscreen",  // tipo de overlay
     *     flashlight: boolean,    // mostrar boton de flash
     *     vibrate: boolean,       // vibrar al escanear
     *     camera: "back"|"front", // camara a usar
     *     title: string           // texto del overlay
     *   }
     * @param {Function} successCallback - Recibe { text, format, rawBytes, template }
     * @param {Function} errorCallback - Recibe string con mensaje de error
     */
    scanQR: function (options, successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'SLMQR', 'scanQR', [options || {}]);
    },

    /**
     * Escanea codigos de barras (EAN, UPC, Code128, etc).
     * @param {Object} options - Opciones de escaneo
     *   {
     *     flashlight: boolean,
     *     vibrate: boolean,
     *     camera: "back"|"front"
     *   }
     * @param {Function} successCallback - Recibe { text, format }
     * @param {Function} errorCallback - Recibe string con mensaje de error
     */
    scanBarcode: function (options, successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'SLMQR', 'scanBarcode', [options || {}]);
    },

    /**
     * Genera una imagen QR en base64.
     * @param {string} data - Contenido a codificar en el QR
     * @param {Object} options - Opciones de generacion
     *   {
     *     size: number,              // px (default 300)
     *     color: string,             // color del QR (default "#000000")
     *     backgroundColor: string,   // color de fondo (default "#FFFFFF")
     *     logo: string,              // base64 de logo al centro (opcional)
     *     errorCorrection: "L"|"M"|"Q"|"H"  // nivel de correccion (default "M")
     *   }
     * @param {Function} successCallback - Recibe { base64Image, format }
     * @param {Function} errorCallback - Recibe string con mensaje de error
     */
    generateQR: function (data, options, successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'SLMQR', 'generateQR', [data, options || {}]);
    },

    openQRPreview: function (options, successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'SLMQR', 'openQRPreview', [options || {}]);
    },

    closeQRPreview: function (successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'SLMQR', 'closeQRPreview', []);
    },

    onQRDetected: function (successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'SLMQR', 'onQRDetected', []);
    }
};

module.exports = SLMQR;
