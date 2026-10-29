import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'capture_oval_painter.dart';
import 'liveness_platform.dart';
import 'models.dart';

/// The face capture screen of the Android Registration Client, for resident capture, operator login and
/// supervisor authentication alike (docs/UI_UX.md, section 8). It behaves like the desktop dialog: the camera
/// notice first, then the live preview with the capture oval, one instruction at a time, the challenge step and
/// countdown, attempts, guidance after a failed attempt, and Retry / Cancel. Model scores and attack details are
/// never shown.
class LivenessCaptureScreen extends StatefulWidget {
  const LivenessCaptureScreen({
    super.key,
    required this.platform,
    required this.workflow,
    required this.title,
    this.subjectKey,
    this.language = 'en',
    this.online,
  });

  final LivenessPlatform platform;
  final LivenessWorkflow workflow;
  final String title;

  /// Who failed attempts count against: the user ID for an operator or supervisor, the registration ID for a
  /// resident.
  final String? subjectKey;

  /// The client's language (ISO 639-1 or 639-3).
  final String language;

  /// Connectivity for the badge, or null to hide it. Liveness behaves the same either way.
  final bool? online;

  /// Opens the screen and returns the result when it closes ([LivenessResult.cancelled] if no session ended).
  static Future<LivenessResult> open(
    BuildContext context, {
    required LivenessPlatform platform,
    required LivenessWorkflow workflow,
    required String title,
    String? subjectKey,
    String language = 'en',
    bool? online,
  }) async {
    final result = await Navigator.of(context).push<LivenessResult>(MaterialPageRoute(
      builder: (_) => LivenessCaptureScreen(
        platform: platform,
        workflow: workflow,
        title: title,
        subjectKey: subjectKey,
        language: language,
        online: online,
      ),
    ));
    return result ?? const LivenessResult.cancelled();
  }

  @override
  State<LivenessCaptureScreen> createState() => _LivenessCaptureScreenState();
}

enum _Phase { loading, consent, running, finished }

class _LivenessCaptureScreenState extends State<LivenessCaptureScreen> {
  static const _closeAfterSuccess = Duration(milliseconds: 1200);
  static const _cancelGrace = Duration(seconds: 2);

  late final StreamSubscription<LivenessEvent> _events;
  LivenessLabels? _labels;
  _Phase _phase = _Phase.loading;
  String? _sessionId;
  CaptureArea _area = CaptureArea.standard;
  LivenessUpdate? _update;
  LivenessResult? _result;
  bool _cameraOn = false;
  bool _closing = false;
  bool _popped = false;
  String? _note;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
    _events = widget.platform.events.listen(_onEvent);
    _loadLabels();
  }

  @override
  void dispose() {
    _events.cancel();
    _timer?.cancel();
    if (_phase == _Phase.running) {
      widget.platform.cancel();
    }
    SystemChrome.setPreferredOrientations(const []);
    super.dispose();
  }

  Future<void> _loadLabels() async {
    final labels = await widget.platform.labels(widget.language);
    if (!mounted) {
      return;
    }
    setState(() {
      _labels = labels;
      _phase = _Phase.consent;
    });
  }

  /// The person's answer to the camera notice. A refusal (or a refused Android permission) still starts the
  /// session: it is audited and ends at once, and the camera is never opened.
  Future<void> _answerConsent(bool allow) async {
    setState(() => _phase = _Phase.running);
    final granted = allow && await widget.platform.requestCameraPermission();
    if (!mounted || _closing) {
      _pop(_result ?? const LivenessResult.cancelled());
      return;
    }
    setState(() => _cameraOn = granted);
    try {
      final start = await widget.platform.start(
        workflow: widget.workflow,
        subjectKey: widget.subjectKey,
        consentGranted: granted,
        language: _labels!.language,
        consentNoticeVersion: _labels!.consentNoticeVersion,
      );
      if (mounted && _phase == _Phase.running) {
        setState(() {
          _sessionId = start.sessionId;
          _area = start.captureArea;
        });
      }
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() {
          _phase = _Phase.finished;
          _cameraOn = false;
          _note = e.message;
        });
      }
    }
  }

  void _onEvent(LivenessEvent event) {
    if (!mounted) {
      return;
    }
    switch (event) {
      // Updates can arrive before start() returns the session ID.
      case LivenessUpdate u when _phase == _Phase.running && (_sessionId == null || u.sessionId == _sessionId):
        setState(() => _update = u);
      case LivenessResult r when _phase == _Phase.running && (_sessionId == null || r.sessionId == _sessionId):
        _finish(r);
      default:
        break;
    }
  }

  void _finish(LivenessResult r) {
    _timer?.cancel();
    setState(() {
      _phase = _Phase.finished;
      _result = r;
      _cameraOn = false;
      if (r.failureReason == 'CONSENT_DECLINED') {
        _note = _labels!.consentFailed;
      } else if (r.lockedUntil != null) {
        _note = _labels!.lockedUntil(TimeOfDay.fromDateTime(r.lockedUntil!).format(context));
      }
    });
    if (_closing) {
      _pop(r);
    } else if (r.accepted) {
      _timer = Timer(_closeAfterSuccess, () => _pop(r));
    }
  }

  void _cancel() {
    if (_phase != _Phase.running) {
      // Nothing running: still loading, at the notice, or already over.
      _pop(_result ?? const LivenessResult.cancelled());
      return;
    }
    setState(() => _closing = true);
    widget.platform.cancel();
    // The cancelled result normally arrives at once; don't hang if it does not (or if no session started yet).
    _timer?.cancel();
    _timer = Timer(_cancelGrace, () => _pop(_result ?? const LivenessResult.cancelled()));
  }

  void _pop(LivenessResult r) {
    if (_popped || !mounted) {
      return;
    }
    _popped = true;
    Navigator.of(context).pop(r);
  }

  @override
  Widget build(BuildContext context) {
    final labels = _labels;
    final direction = labels?.rtl == true ? TextDirection.rtl : TextDirection.ltr;
    return PopScope<LivenessResult>(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) {
          _cancel();
        }
      },
      child: Scaffold(
        backgroundColor: Colors.white,
        appBar: AppBar(
          title: Text(widget.title),
          automaticallyImplyLeading: false,
          actions: [
            IconButton(
              icon: const Icon(Icons.close),
              tooltip: labels?.cancel,
              onPressed: _closing ? null : _cancel,
            ),
          ],
        ),
        body: SafeArea(
          child: labels == null
              ? const Center(child: CircularProgressIndicator())
              : Column(
                  children: [
                    Expanded(flex: 5, child: _preview(context)),
                    Expanded(
                      flex: 4,
                      child: Directionality(
                        textDirection: direction,
                        child: _phase == _Phase.consent ? _consent(labels) : _status(labels),
                      ),
                    ),
                    if (_phase != _Phase.consent)
                      Directionality(textDirection: direction, child: _buttons(labels)),
                  ],
                ),
        ),
      ),
    );
  }

  /// The preview is never flipped for right-to-left languages: it is a mirror of the person.
  Widget _preview(BuildContext context) {
    final state = _update?.state ?? 'NOT_STARTED';
    return ColoredBox(
      color: LivenessColors.background,
      child: Center(
        child: AspectRatio(
          aspectRatio: 3 / 4,
          child: Stack(
            fit: StackFit.expand,
            children: [
              if (_cameraOn && _phase == _Phase.running) widget.platform.buildPreview(context),
              CustomPaint(
                painter: CaptureOvalPainter(
                  area: _area,
                  state: _phase == _Phase.finished && _result?.accepted == false ? 'FAILED' : state,
                  challenge: _update?.challenge,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _consent(LivenessLabels labels) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(labels.consentTitle, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
          const SizedBox(height: 12),
          Text(labels.consentText, style: const TextStyle(fontSize: 16, color: LivenessColors.text)),
          const SizedBox(height: 20),
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              // "Don't allow" first and filled: consent has to be an explicit choice.
              FilledButton(
                key: const Key('liveness-deny'),
                style: _buttonStyle,
                onPressed: () => _answerConsent(false),
                child: Text(labels.deny),
              ),
              const SizedBox(width: 12),
              OutlinedButton(
                key: const Key('liveness-allow'),
                style: _buttonStyle,
                onPressed: () => _answerConsent(true),
                child: Text(labels.allow),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _status(LivenessLabels labels) {
    final u = _update;
    final finished = _phase == _Phase.finished;
    final accepted = _result?.accepted ?? false;
    final state = u?.state ?? 'NOT_STARTED';
    final step = [u?.stepText, u?.timeLeftText].whereType<String>().join(' · ');
    final showTips = u != null && (state == 'ATTEMPT_FAILED' || finished && !accepted);
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (u != null)
            Text(
              u.messageText,
              key: const Key('liveness-message'),
              style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: LivenessColors.textFor(state)),
            ),
          if (u?.guidanceText != null) ...[
            const SizedBox(height: 4),
            Text(u!.guidanceText!, style: const TextStyle(fontSize: 15, color: LivenessColors.muted)),
          ],
          if (step.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(step, key: const Key('liveness-step'), style: const TextStyle(color: LivenessColors.muted)),
          ],
          if (!finished) ...[
            const SizedBox(height: 10),
            LinearProgressIndicator(value: u?.progress, minHeight: 6),
          ],
          const SizedBox(height: 8),
          Row(
            children: [
              if (!finished && u?.attemptText != null)
                Expanded(child: Text(u!.attemptText!, style: const TextStyle(color: LivenessColors.muted)))
              else
                const Spacer(),
              if (widget.online != null) _badge(labels),
            ],
          ),
          if (showTips) ...[
            const SizedBox(height: 10),
            for (final tip in u.tips)
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Text('• $tip', style: const TextStyle(fontSize: 15, color: LivenessColors.text)),
              ),
            if (state == 'ATTEMPT_FAILED' && u.retryAvailable)
              Text(labels.pressRetry, style: const TextStyle(color: LivenessColors.muted)),
          ],
          if (_note != null) ...[
            const SizedBox(height: 10),
            Text(_note!, key: const Key('liveness-note'), style: const TextStyle(fontSize: 15, color: LivenessColors.text)),
          ],
          const SizedBox(height: 12),
          Text(labels.onDevice, style: const TextStyle(fontSize: 12, color: LivenessColors.muted)),
        ],
      ),
    );
  }

  Widget _badge(LivenessLabels labels) {
    final online = widget.online!;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(Icons.circle, size: 10, color: online ? LivenessColors.guideSuccess : LivenessColors.muted),
        const SizedBox(width: 6),
        Text(online ? labels.online : labels.offline, style: const TextStyle(color: LivenessColors.muted)),
      ],
    );
  }

  Widget _buttons(LivenessLabels labels) {
    final finished = _phase == _Phase.finished;
    final canRetry = !finished && !_closing && (_update?.retryAvailable ?? false);
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 16),
      child: Row(
        children: [
          Expanded(
            child: FilledButton(
              key: const Key('liveness-retry'),
              style: _buttonStyle,
              onPressed: canRetry ? () => widget.platform.retry() : null,
              child: Text(labels.retry),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: OutlinedButton(
              key: const Key('liveness-cancel'),
              style: _buttonStyle,
              onPressed: _closing && !finished
                  ? null
                  : (finished ? () => _pop(_result ?? const LivenessResult.cancelled()) : _cancel),
              child: Text(finished ? labels.close : labels.cancel),
            ),
          ),
        ],
      ),
    );
  }

  // At least 48 dp high: comfortable touch targets on a tablet at a registration desk.
  static final ButtonStyle _buttonStyle = ButtonStyle(
    minimumSize: WidgetStateProperty.all(const Size(120, 52)),
    textStyle: WidgetStateProperty.all(const TextStyle(fontSize: 16)),
  );
}
