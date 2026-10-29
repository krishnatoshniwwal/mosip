import 'dart:async';

import 'package:flutter/material.dart';
import 'package:mosip_face_liveness/mosip_face_liveness.dart';

/// A stand-in for the three Android Registration Client screens that use the face: operator login, resident
/// biometrics and supervisor approval. Each opens [LivenessCaptureScreen] the way the client would; the client's
/// own face match is simulated. "Simulated camera" runs on an emulator without a camera, and lets you put a
/// printed photo, a screen or a replayed video in front of the camera instead of a person.
void main() => runApp(const LivenessExampleApp());

/// Stands in for the synced global parameters (`mosip.registration.liveness.*`), with the same per-workflow
/// policies as config/liveness.properties in the repository.
const Map<String, String> syncedLivenessSettings = {
  'mosip.registration.liveness.resident.retry.exhaustedAction': 'REQUIRE_SUPERVISOR_OVERRIDE',
  'mosip.registration.liveness.operator.retry.exhaustedAction': 'ALLOW_ALTERNATE_AUTH',
  'mosip.registration.liveness.operator.occlusion.allowCapture': 'true',
  'mosip.registration.liveness.supervisor.passive.threshold': '80',
  'mosip.registration.liveness.supervisor.active.always': 'true',
  'mosip.registration.liveness.supervisor.retry.max': '1',
  'mosip.registration.liveness.supervisor.rateLimit.maxFailures': '3',
};

const _languages = {
  'en': 'English',
  'fr': 'Français',
  'ar': 'العربية',
  'hi': 'हिन्दी',
  'kn': 'ಕನ್ನಡ',
  'ta': 'தமிழ்',
  'fil': 'Filipino',
  'am': 'አማርኛ',
  'si': 'සිංහල',
};

class LivenessExampleApp extends StatelessWidget {
  const LivenessExampleApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'MOSIP face liveness',
        theme: ThemeData(colorSchemeSeed: const Color(0xFF1E5AA8), useMaterial3: true),
        home: const HomePage(),
      );
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final LivenessPlatform _liveness = MethodChannelLivenessPlatform();
  final List<String> _audit = [];
  late final StreamSubscription<LivenessEvent> _events;

  bool _simulated = true;
  bool _ready = false;
  String? _engine;
  String? _error;
  String _language = 'en';
  String _presentation = 'LIVE';
  bool _online = true;

  final _operator = TextEditingController(text: '110011');
  final _supervisor = TextEditingController(text: '110012');
  final String _rid = '10001100010002${DateTime.now().millisecondsSinceEpoch % 1000000}'.padRight(20, '0');
  LivenessResult? _residentResult;
  final Map<LivenessWorkflow, String> _status = {};

  @override
  void initState() {
    super.initState();
    _events = _liveness.events.listen((e) {
      if (e is LivenessAuditEvent) {
        setState(() => _audit.insert(0, '${e.code} ${e.type} ${e.details}'));
      }
    });
    _initialize();
  }

  @override
  void dispose() {
    _events.cancel();
    _liveness.dispose();
    super.dispose();
  }

  Future<void> _initialize() async {
    setState(() {
      _ready = false;
      _error = null;
    });
    try {
      await _liveness.dispose();
      final info = await _liveness.initialize(simulated: _simulated, config: syncedLivenessSettings);
      if (_simulated) {
        await _liveness.simulate(_presentation);
      }
      setState(() {
        _ready = true;
        _engine = info['engine'] as String?;
      });
    } catch (e) {
      setState(() => _error = '$e');
    }
  }

  Future<void> _verify(LivenessWorkflow workflow, String title, String subjectKey) async {
    setState(() => _status.remove(workflow));
    final result = await LivenessCaptureScreen.open(context,
        platform: _liveness,
        workflow: workflow,
        title: title,
        subjectKey: subjectKey,
        language: _language,
        online: _online);
    if (!mounted) {
      return;
    }
    setState(() {
      if (workflow == LivenessWorkflow.residentRegistration) {
        _residentResult = result;
      }
      _status[workflow] = result.accepted ? _accepted(workflow, subjectKey) : _notAccepted(result);
    });
  }

  /// The client's next step; in the real client the face match runs on result.capturedJpeg.
  static String _accepted(LivenessWorkflow workflow, String subjectKey) => switch (workflow) {
        LivenessWorkflow.residentRegistration => 'Face captured for $subjectKey after liveness verification.',
        LivenessWorkflow.operatorAuthentication => 'Face matched. Logged in as $subjectKey.',
        LivenessWorkflow.supervisorAuthentication => 'Supervisor $subjectKey verified. Packets approved.',
      };

  static String _notAccepted(LivenessResult r) => switch (r.requiredAction) {
        _ when r.outcome == 'CANCELLED' => 'Cancelled.',
        'REQUIRE_SUPERVISOR_OVERRIDE' => 'Not verified: send to the supervisor-approved exception flow.',
        'ALLOW_ALTERNATE_AUTH' => 'Not verified: use password or OTP login instead.',
        'BLOCK' => 'Not verified: face verification is blocked for now.',
        _ => 'Face verification was not completed.',
      };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Registration Client – face liveness'),
        actions: [
          DropdownButton<String>(
            value: _language,
            underline: const SizedBox(),
            items: [for (final e in _languages.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
            onChanged: (v) => setState(() => _language = v!),
          ),
          const SizedBox(width: 12),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _setup(),
          const SizedBox(height: 12),
          if (_error != null)
            Card(
              color: Theme.of(context).colorScheme.errorContainer,
              child: Padding(padding: const EdgeInsets.all(12), child: Text(_error!)),
            ),
          if (!_ready && _error == null) const LinearProgressIndicator(),
          _screen(
            'Operator login',
            'The operator\'s face is checked for liveness, then matched against the enrolled face.',
            LivenessWorkflow.operatorAuthentication,
            field: _field('Username', _operator),
            action: 'Login with face',
            subjectKey: () => _operator.text,
          ),
          _screen(
            'New registration – face',
            'The resident\'s face is checked for liveness; the face that passed goes into the packet.',
            LivenessWorkflow.residentRegistration,
            field: Row(children: [
              Expanded(child: Text('Registration ID\n$_rid')),
              Container(
                width: 90,
                height: 120,
                decoration: BoxDecoration(border: Border.all(color: Colors.black26)),
                child: _residentResult?.capturedJpeg == null
                    ? const Center(child: Icon(Icons.face, color: Colors.black26))
                    : Image.memory(_residentResult!.capturedJpeg!, fit: BoxFit.cover),
              ),
            ]),
            action: 'Scan face',
            subjectKey: () => _rid,
          ),
          _screen(
            'Supervisor approval',
            'Packet approval and end-of-day sign-off. Always asks for an active challenge; one retry.',
            LivenessWorkflow.supervisorAuthentication,
            field: _field('Supervisor ID', _supervisor),
            action: 'Authenticate with face',
            subjectKey: () => _supervisor.text,
          ),
          ExpansionTile(
            title: const Text('Diagnostics (audit trail, no biometric data)'),
            subtitle: _engine == null ? null : Text(_engine!, maxLines: 2, overflow: TextOverflow.ellipsis),
            children: [
              for (final line in _audit.take(60))
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
                  child: Text(line, style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _setup() => Card(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Wrap(
            spacing: 16,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              SegmentedButton<bool>(
                segments: const [
                  ButtonSegment(value: false, label: Text('Camera'), icon: Icon(Icons.photo_camera_front)),
                  ButtonSegment(value: true, label: Text('Simulated camera'), icon: Icon(Icons.science)),
                ],
                selected: {_simulated},
                onSelectionChanged: (s) {
                  setState(() => _simulated = s.first);
                  _initialize();
                },
              ),
              if (_simulated)
                DropdownButton<String>(
                  value: _presentation,
                  items: const [
                    DropdownMenuItem(value: 'LIVE', child: Text('Live person')),
                    DropdownMenuItem(value: 'PRINTED_PHOTO', child: Text('Printed photo')),
                    DropdownMenuItem(value: 'SCREEN_DISPLAY', child: Text('Photo on a screen')),
                    DropdownMenuItem(value: 'VIDEO_REPLAY', child: Text('Video replay')),
                    DropdownMenuItem(value: 'NO_FACE', child: Text('Nobody')),
                    DropdownMenuItem(value: 'TWO_FACES', child: Text('Two people')),
                  ],
                  onChanged: (v) {
                    setState(() => _presentation = v!);
                    _liveness.simulate(v!);
                  },
                ),
              FilterChip(
                label: const Text('Network online'),
                selected: _online,
                onSelected: (v) => setState(() => _online = v),
              ),
            ],
          ),
        ),
      );

  Widget _field(String label, TextEditingController controller) =>
      TextField(controller: controller, decoration: InputDecoration(labelText: label));

  Widget _screen(String title, String note, LivenessWorkflow workflow,
      {required Widget field, required String action, required String Function() subjectKey}) {
    final status = _status[workflow];
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 4),
            Text(note, style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(height: 12),
            field,
            const SizedBox(height: 12),
            FilledButton(
              onPressed: _ready ? () => _verify(workflow, title, subjectKey()) : null,
              child: Text(action),
            ),
            if (status != null) ...[
              const SizedBox(height: 8),
              Text(status, style: const TextStyle(fontWeight: FontWeight.bold)),
            ],
          ],
        ),
      ),
    );
  }
}
