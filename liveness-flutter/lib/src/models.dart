import 'dart:typed_data';

/// Which screen is capturing the face. Each has its own policy (thresholds, challenges, retries, what happens
/// after the last failure), set in the client's `mosip.registration.liveness.*` configuration.
enum LivenessWorkflow {
  residentRegistration('RESIDENT_REGISTRATION'),
  operatorAuthentication('OPERATOR_AUTHENTICATION'),
  supervisorAuthentication('SUPERVISOR_AUTHENTICATION');

  const LivenessWorkflow(this.wireName);

  /// The name the native side uses.
  final String wireName;
}

/// The oval the face must be inside, normalised to the upright, unmirrored camera frame. The session enforces
/// exactly this area; the screen draws it.
class CaptureArea {
  const CaptureArea({
    required this.enabled,
    required this.centerX,
    required this.centerY,
    required this.radiusX,
    required this.radiusY,
  });

  /// The built-in default, used until a session reports its own.
  static const CaptureArea standard =
      CaptureArea(enabled: true, centerX: 0.5, centerY: 0.48, radiusX: 0.22, radiusY: 0.37);

  factory CaptureArea.fromMap(Map<Object?, Object?> map) => CaptureArea(
        enabled: map['enabled'] as bool? ?? true,
        centerX: _double(map['centerX']),
        centerY: _double(map['centerY']),
        radiusX: _double(map['radiusX']),
        radiusY: _double(map['radiusY']),
      );

  final bool enabled;
  final double centerX;
  final double centerY;
  final double radiusX;
  final double radiusY;
}

/// Button, notice and badge texts in the session's language, from the same bundles the desktop client uses.
class LivenessLabels {
  LivenessLabels(Map<Object?, Object?> map)
      : _texts = {
          for (final e in map.entries)
            if (e.value is String) e.key as String: e.value as String,
        },
        rtl = map['rtl'] as bool? ?? false;

  final Map<String, String> _texts;

  /// Right-to-left layout (Arabic). The camera preview is never flipped for it.
  final bool rtl;

  String get language => _texts['language'] ?? 'en';
  String get consentNoticeVersion => _texts['consentNoticeVersion'] ?? '';
  String get retry => _texts['retry'] ?? 'Retry';
  String get cancel => _texts['cancel'] ?? 'Cancel';
  String get close => _texts['close'] ?? 'Close';
  String get pressRetry => _texts['pressRetry'] ?? '';
  String get consentTitle => _texts['consentTitle'] ?? '';
  String get consentText => _texts['consentText'] ?? '';
  String get allow => _texts['allow'] ?? 'Allow';
  String get deny => _texts['deny'] ?? "Don't allow";
  String get onDevice => _texts['onDevice'] ?? '';
  String get online => _texts['online'] ?? 'Online';
  String get offline => _texts['offline'] ?? 'Offline';
  String get consentFailed => _texts['consentFailed'] ?? '';

  /// "Locked until {0}" with the time filled in. The pattern is a Java MessageFormat one, where an apostrophe is
  /// written twice.
  String lockedUntil(String time) =>
      (_texts['lockedUntil'] ?? 'Locked until {0}').replaceAll("''", "'").replaceAll('{0}', time);
}

/// What `start` returns.
class SessionStart {
  SessionStart.fromMap(Map<Object?, Object?> map)
      : sessionId = map['sessionId'] as String,
        captureArea = map['captureArea'] is Map
            ? CaptureArea.fromMap(map['captureArea'] as Map<Object?, Object?>)
            : CaptureArea.standard;

  final String sessionId;
  final CaptureArea captureArea;
}

/// Something the native side reports while a session runs.
sealed class LivenessEvent {
  const LivenessEvent();

  /// Null for an event type this version does not know.
  static LivenessEvent? fromMap(Map<Object?, Object?> map) => switch (map['eventType']) {
        'update' => LivenessUpdate.fromMap(map),
        'complete' => LivenessResult.fromMap(map),
        'audit' => LivenessAuditEvent.fromMap(map),
        'cameraError' => CameraErrorEvent(map['message'] as String? ?? ''),
        'modelSync' => ModelSyncEvent(map['status'] as String? ?? '', map['detail'] as String?, map['offered'] as String?),
        _ => null,
      };
}

/// One step of the session: what to tell the person now. All texts arrive translated.
class LivenessUpdate extends LivenessEvent {
  LivenessUpdate.fromMap(Map<Object?, Object?> map)
      : sessionId = map['sessionId'] as String,
        state = map['state'] as String,
        messageCode = map['messageCode'] as String? ?? '',
        messageText = map['messageText'] as String? ?? map['message'] as String? ?? '',
        guidanceText = map['guidanceText'] as String? ?? map['guidance'] as String?,
        tips = ((map['tipsText'] ?? map['tips']) as List<Object?>? ?? const []).cast<String>(),
        challenge = map['challenge'] as String?,
        stepText = map['stepText'] as String?,
        timeLeftText = map['timeLeftText'] as String?,
        attemptText = map['attemptText'] as String?,
        progress = _double(map['progress']),
        retryAvailable = map['retryAvailable'] as bool? ?? false;

  final String sessionId;

  /// NOT_STARTED, PASSIVE_CHECK, ACTIVE_CHALLENGE, CHALLENGE_FEEDBACK, HOLD_STILL, ATTEMPT_FAILED, PASSED, FAILED,
  /// CANCELLED.
  final String state;
  final String messageCode;
  final String messageText;
  final String? guidanceText;
  final List<String> tips;

  /// BLINK, SMILE, TURN_LEFT, TURN_RIGHT, LOOK_UP or LOOK_DOWN while a challenge is shown.
  final String? challenge;
  final String? stepText;
  final String? timeLeftText;
  final String? attemptText;
  final double progress;
  final bool retryAvailable;
}

/// The end of a session. Use [capturedJpeg] only when [accepted]; otherwise follow [requiredAction].
class LivenessResult extends LivenessEvent {
  LivenessResult.fromMap(Map<Object?, Object?> map)
      : sessionId = map['sessionId'] as String?,
        outcome = map['outcome'] as String,
        accepted = map['accepted'] as bool? ?? false,
        failureReason = map['failureReason'] as String?,
        requiredAction = map['requiredAction'] as String?,
        attempts = map['attempts'] as int? ?? 0,
        durationMs = map['durationMs'] as int? ?? 0,
        challengesCompleted = (map['challengesCompleted'] as List<Object?>? ?? const []).cast<String>(),
        passiveScore = map['passiveScore'] == null ? null : _double(map['passiveScore']),
        capturedJpeg = map['capturedJpeg'] as Uint8List?,
        lockedUntil = map['lockedUntil'] == null
            ? null
            : DateTime.fromMillisecondsSinceEpoch(map['lockedUntil'] as int);

  /// The screen was closed before any session ended.
  const LivenessResult.cancelled()
      : sessionId = null,
        outcome = 'CANCELLED',
        accepted = false,
        failureReason = null,
        requiredAction = null,
        attempts = 0,
        durationMs = 0,
        challengesCompleted = const [],
        passiveScore = null,
        capturedJpeg = null,
        lockedUntil = null;

  final String? sessionId;

  /// PASSED, LIVENESS_DISABLED, FAILED or CANCELLED.
  final String outcome;
  final bool accepted;
  final String? failureReason;

  /// After the last failure: BLOCK, REQUIRE_SUPERVISOR_OVERRIDE or ALLOW_ALTERNATE_AUTH.
  final String? requiredAction;
  final int attempts;
  final int durationMs;
  final List<String> challengesCompleted;

  /// For the caller's records. Never show it on the capture screen.
  final double? passiveScore;

  /// The face that passed liveness, as JPEG: store or match exactly this image.
  final Uint8List? capturedJpeg;
  final DateTime? lockedUntil;
}

/// An audit record (no biometric data), for a diagnostics view.
class LivenessAuditEvent extends LivenessEvent {
  LivenessAuditEvent.fromMap(Map<Object?, Object?> map)
      : code = map['code'] as String? ?? '',
        type = map['type'] as String? ?? '',
        attempt = map['attempt'] as int? ?? 0,
        details = {
          for (final e in (map['details'] as Map<Object?, Object?>? ?? const {}).entries)
            e.key.toString(): e.value.toString(),
        };

  final String code;
  final String type;
  final int attempt;
  final Map<String, String> details;
}

/// A background model update attempt (only while online): UPDATED, UP_TO_DATE, OFFLINE or REJECTED. An update is
/// used from the next [LivenessPlatform.initialize].
class ModelSyncEvent extends LivenessEvent {
  const ModelSyncEvent(this.status, this.detail, this.offered);

  final String status;
  final String? detail;

  /// The package the server offered, e.g. `liveness-models 1.3.0 (seq 2)`.
  final String? offered;
}

/// The camera could not be started (none, in use, ...). The session reports the failure itself as well.
class CameraErrorEvent extends LivenessEvent {
  const CameraErrorEvent(this.message);

  final String message;
}

double _double(Object? value) => value is num ? value.toDouble() : 0;
