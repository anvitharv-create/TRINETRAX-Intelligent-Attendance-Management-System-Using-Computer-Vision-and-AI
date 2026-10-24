/**
 * Mock Face Recognition Service (Client-Side)
 * 
 * This provides simulated face detection and recognition that runs entirely
 * in the browser. Uses simple color/pattern-based simulation for demonstration.
 * 
 * For production, replace with:
 * - face-recognition library (dlib-based)
 * - MediaPipe Face Detection + Face Landmarks
 * - TensorFlow.js with MobileNet or FaceNet
 * - Onnx.js with OpenCV.js face models
 */

export class FaceEncoding {
  constructor(values, studentId, name) {
    this.values = values;
    this.studentId = studentId;
    this.name = name;
  }
}

/**
 * Mock recognition service - runs entirely in browser
 * No Python dependencies, no native modules needed
 */
export default class MockRecognitionService {
  constructor() {
    // Mock: Predefined face encodings for registered students
    this.knownFaces = [
      new FaceEncoding(
        [0.45, 0.32, 0.89, 0.12, 0.67, 0.55, 0.23, 0.78, 0.34, 0.91],
        "STU-001",
        "Alice Johnson"
      ),
      new FaceEncoding(
        [0.12, 0.67, 0.23, 0.89, 0.45, 0.33, 0.77, 0.11, 0.55, 0.22],
        "STU-002",
        "Bob Smith"
      ),
      new FaceEncoding(
        [0.65, 0.11, 0.44, 0.77, 0.33, 0.99, 0.22, 0.55, 0.11, 0.66],
        "STU-003",
        "Carol Davis"
      ),
      new FaceEncoding(
        [0.22, 0.44, 0.66, 0.11, 0.33, 0.55, 0.77, 0.88, 0.99, 0.12],
        "STU-004",
        "David Wilson"
      ),
      new FaceEncoding(
        [0.55, 0.77, 0.11, 0.33, 0.99, 0.22, 0.44, 0.66, 0.88, 0.12],
        "STU-005",
        "Eve Martin"
      ),
    ];
  }

  /**
   * Detect faces in an image using a simple simulation
   * @param {HTMLImageElement|HTMLVideoElement} videoElement - Video element containing the frame
   * @returns {Object} Detection results
   */
  detectFaces(videoElement) {
    // Mock: Simulate face detection
    // In a real implementation, would use:
    // - MediaPipe face detection
    // - Haar Cascades with opencv.js
    // - face-recognition library
    
    // For demo, return mock detection of 4-6 faces
    const mockFaceCount = 4 + Math.floor(Math.random() * 3); // 4-6 faces
    
    const faceLocations = [];
    for (let i = 0; i < mockFaceCount; i++) {
      // Generate realistic-ish face locations within video bounds
      const top = 80 + Math.random() * (videoElement.videoHeight - 200);
      const left = 50 + Math.random() * (videoElement.videoWidth - 300);
      const right = left + (100 + Math.random() * 100);
      const bottom = top + (120 + Math.random() * 80);
      
      faceLocations.push([top, right, bottom, left]);
    }
    
    return {
      success: true,
      numFaces: mockFaceCount,
      faceLocations: faceLocations,
      method: "mock-simulation"
    };
  }

  /**
   * Recognize faces against known students
   * @param {HTMLImageElement|HTMLVideoElement} videoElement - Video element with current frame
   * @param {number} tolerance - Matching tolerance (0.0-1.0, 0.6 default)
   * @returns {Array} Recognition results for each face
   */
  recognizeFaces(videoElement, tolerance = 0.6) {
    const detection = this.detectFaces(videoElement);
    
    if (!detection.success || detection.numFaces === 0) {
      return [];
    }

    const results = [];
    
    // For mock, assign recognition results to each detected face
    for (let i = 0; i < detection.numFaces; i++) {
      // Randomly assign: either a known student or unknown
      const isKnown = Math.random() > 0.3; // 70% known, 30% unknown
      
      if (isKnown) {
        // Pick a random known student
        const randomIdx = Math.floor(Math.random() * this.knownFaces.length);
        const face = this.knownFaces[randomIdx];
        
        // Generate a confidence score based on "match quality"
        const baseConfidence = 0.85 + Math.random() * 0.1; // 0.85-0.95
        const confidence = baseConfidence;
        
        results.push({
          studentId: face.studentId,
          name: face.name,
          confidence: confidence,
          encoding: face.values,
          isKnown: true
        });
      } else {
        results.push({
          studentId: "unknown",
          name: "Unknown Person",
          confidence: 0.2 + Math.random() * 0.3, // 0.2-0.5
          encoding: null,
          isKnown: false
        });
      }
    }
    
    return results;
  }

  /**
   * Simulate face encoding extraction from a video frame
   * @param {HTMLVideoElement} videoElement - Video element with current frame
   * @returns {Array|null} Face encoding or null if no face found
   */
  extractEncoding(videoElement) {
    // Mock: Return a random known face encoding or null
    if (Math.random() > 0.3 && this.knownFaces.length > 0) {
      const randomIdx = Math.floor(Math.random() * this.knownFaces.length);
      return this.knownFaces[randomIdx].values;
    }
    return null;
  }

  /**
   * Register a new student face (mock - just logs in browser)
   * @param {string} studentId - Student ID
   * @param {string} name - Student name
   * @param {Array} encoding - Face encoding values
   */
  registerStudent(studentId, name, encoding) {
    console.log(`Mock: Registering student ${name} (${studentId}) with encoding of length ${encoding?.length || 0}`);
    // In a real system, this would send the encoding to the backend
    // and store it securely for future recognition
  }
}

/**
 * Helper function to create the mock service instance
 * @returns {MockRecognitionService} Recognition service instance
 */
export const createMockService = () => new MockRecognitionService();