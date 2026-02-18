// Get the meter display element
const meter = document.getElementById("meter");

// Audio variables
let audioContext;
let analyser;
let microphone;
let dataArray;

// Start when user clicks (required by browser)
document.body.addEventListener("click", async () => {

    meter.innerText = "Requesting microphone access...";

    // Create audio context
    audioContext = new (window.AudioContext || window.webkitAudioContext)();

    // Ask for microphone access
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });

    // Create microphone source
    microphone = audioContext.createMediaStreamSource(stream);

    // Create analyser to read audio data
    analyser = audioContext.createAnalyser();
    analyser.fftSize = 2048;

    // Connect mic to analyser
    microphone.connect(analyser);

    // Create array to store audio samples
    dataArray = new Uint8Array(analyser.fftSize);

    meter.innerText = "Listening...";

    update();
});

// Runs continuously to measure volume
function update() {

    // Get waveform data (0–255 values)
    analyser.getByteTimeDomainData(dataArray);

    let sum = 0;

    // Convert values to range -1 to 1 and calculate RMS
    for (let i = 0; i < dataArray.length; i++) {
        let normalized = (dataArray[i] - 128) / 128;
        sum += normalized * normalized;
    }

    let rms = Math.sqrt(sum / dataArray.length);

    // Convert to decibels
    let db = 20 * Math.log10(rms);

    // Prevent negative infinity when silent
    if (db === -Infinity) db = -100;

    // Display result
    meter.innerText = "Volume: " + db.toFixed(2) + " dB";

    // Repeat
    requestAnimationFrame(update);
}
