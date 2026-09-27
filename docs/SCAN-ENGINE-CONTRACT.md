# Scan Engine Contract

Highest-priority product capability: scanning a document.

## Goal

Given a live or fixture camera sequence containing a document, CamScan turns it into a clean page artifact with behavior comparable to the reference scan workflow.

## Required seams

The implementation must preserve replaceable interfaces equivalent to:

~~~
DocumentDetector.detect(frame) -> DocumentDetection?
DetectionStabilizer.update(detection) -> StableDetection?
PerspectiveCorrector.correct(image, quad) -> ProcessedPage
EnhancementEngine.process(page, mode) -> ProcessedPage

ScanSession:
  addPage
  retake
  remove
  reorder
  finish
~~~

Exact class names may vary, but the seams may not disappear.

## Detection

Detector output:
- four ordered corners;
- confidence;
- quality flags;
- capture timestamp.

Stable detection uses temporal consistency. The UI must not jump arbitrarily between corners.

Reject or degrade gracefully for:
- no page;
- partial page;
- severe blur;
- extreme glare;
- insufficient contrast;
- unstable motion.

## Geometry

For a valid quadrilateral:
1. order corners consistently;
2. estimate target dimensions;
3. compute perspective transform;
4. render a rectangular page;
5. retain source-to-page geometry metadata.

Keep the original capture for non-destructive editing/reprocessing.

## Enhancement

Enhancement is deterministic and non-destructive. The same input/settings must produce a semantically identical result; where the selected library permits, output should also be byte-stable.

## Review

After capture the user can:
- inspect;
- accept;
- retake;
- crop/adjust;
- rotate;
- change enhancement.

## Multi-page

A session contains N pages while preserving:
- order;
- per-page settings;
- retake/delete state;
- stable final document id.

## Export

PDF:
- opens;
- correct page count;
- correct order;
- correct orientation;
- correct page dimensions.

JPG:
- requested page(s);
- correct orientation;
- selected enhancement result.

## Regression corpus

Use:
clean-a4, skewed-document, receipt, business-card, handwritten, low-light, multi-page, document-pan and camera-motion.

Use fixture ground truth for algorithmic quality and reference evidence for behavioral parity.

## Required reference scenarios

Turn reference behavior into executable scenarios for:
launch -> scan -> permission -> detection -> capture -> crop -> enhancement -> accept -> retake -> multi-page -> save -> export.

A scan implementation is never accepted because an output image merely looks good. The full observable user journey must work.
