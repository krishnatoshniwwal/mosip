import 'package:flutter/widgets.dart';

import 'models.dart';

/// Colours shared with the desktop capture dialog.
abstract final class LivenessColors {
  static const background = Color(0xFF1D2329);
  static const guideNeutral = Color(0xFFDDE3E8);
  static const guideActive = Color(0xFFF5A623);
  static const guideSuccess = Color(0xFF2ECC71);
  static const guideError = Color(0xFFE74C3C);
  static const text = Color(0xFF1F2933);
  static const muted = Color(0xFF52606D);
  static const textActive = Color(0xFFB86E00);
  static const textSuccess = Color(0xFF1E8E4E);
  static const textError = Color(0xFFC0392B);

  static Color guideFor(String state) => switch (state) {
        'ACTIVE_CHALLENGE' => guideActive,
        'CHALLENGE_FEEDBACK' || 'HOLD_STILL' || 'PASSED' => guideSuccess,
        'ATTEMPT_FAILED' || 'FAILED' => guideError,
        _ => guideNeutral,
      };

  static Color textFor(String state) => switch (state) {
        'ACTIVE_CHALLENGE' => textActive,
        'CHALLENGE_FEEDBACK' || 'HOLD_STILL' || 'PASSED' => textSuccess,
        'ATTEMPT_FAILED' || 'FAILED' => textError,
        _ => text,
      };
}

/// The capture oval over the mirrored preview, in the session's own coordinates, and an arrow for a head turn or
/// look. The area is in unmirrored frame coordinates, so its centre is mirrored here like the preview.
class CaptureOvalPainter extends CustomPainter {
  CaptureOvalPainter({required this.area, required this.state, this.challenge});

  final CaptureArea area;
  final String state;
  final String? challenge;

  @override
  void paint(Canvas canvas, Size size) {
    if (!area.enabled) {
      return;
    }
    final width = 2 * area.radiusX * size.width;
    final height = 2 * area.radiusY * size.height;
    final center = Offset((1 - area.centerX) * size.width, area.centerY * size.height);
    final oval = Rect.fromCenter(center: center, width: width, height: height);
    final paint = Paint()
      ..color = LivenessColors.guideFor(state)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.round;
    for (final metric in (Path()..addOval(oval)).computeMetrics()) {
      for (double d = 0; d < metric.length; d += 18) {
        canvas.drawPath(metric.extractPath(d, d + 10), paint);
      }
    }
    if (state != 'ACTIVE_CHALLENGE') {
      return;
    }
    final reach = width / 2 + 30;
    switch (challenge) {
      case 'TURN_LEFT':
        _arrow(canvas, center.translate(-reach, 0), const Offset(-1, 0));
      case 'TURN_RIGHT':
        _arrow(canvas, center.translate(reach, 0), const Offset(1, 0));
      case 'LOOK_UP':
        _arrow(canvas, center.translate(0, -height / 2 - 12), const Offset(0, -1));
      case 'LOOK_DOWN':
        _arrow(canvas, center.translate(0, height / 2 + 12), const Offset(0, 1));
    }
  }

  void _arrow(Canvas canvas, Offset at, Offset dir) {
    const len = 16.0;
    final normal = Offset(-dir.dy, dir.dx);
    final path = Path()
      ..moveTo(at.dx + dir.dx * len, at.dy + dir.dy * len)
      ..lineTo(at.dx - dir.dx * len + normal.dx * len, at.dy - dir.dy * len + normal.dy * len)
      ..lineTo(at.dx - dir.dx * len - normal.dx * len, at.dy - dir.dy * len - normal.dy * len)
      ..close();
    canvas.drawPath(path, Paint()..color = LivenessColors.guideActive);
  }

  @override
  bool shouldRepaint(CaptureOvalPainter old) =>
      old.state != state || old.challenge != challenge || old.area != area;
}
