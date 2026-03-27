# Implementation Plan: Fix Layer Compositing & Clipping Bugs

## Goal Description
The `CanvasDocument.rebuildCompositeTile` function determines how all layers are blended into the final canvas representation shown to the user. Its current implementation contains fatal logical flaws related to layer clipping groups:
1. When a base layer is empty (has no tiles drawn on it), `clipBaseTile` isn't properly cleared or bypassed, causing any layers clipped to it to incorrectly clip to *whatever unrelated base layer was below it*.
2. When a base layer is hidden (`!isVisible` or `opacity == 0`), the iteration completely skips the base layer WITHOUT updating `clipBaseTile`, creating the same bug—clipped layers will clip to unrelated bases elsewhere in the stack, or worse, continue to be drawn when they should be hidden.

This fixes the issue reported where "キャンバスのレイヤーシステムにも問題があってクリッピングを適用しても適切に表示されません。" (Clipping fails to display correctly).

The second bug reported ("ブラシを交互に切り替えて使ったりなどするとキャンバスに適切にレンダリングされなくなります") is caused by a critical flaw in `PixelOps.unComposite` and `BrushEngine.blurAreaOnSurface`.
When [blurAreaOnSurface](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/engine/BrushEngine.kt#362-454) blends the current stroke (`sublayer`) with the canvas content, blurs it, and then tries to [unComposite](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/engine/PixelOps.kt#333-352) the blurred result back into the `sublayer`, it fails drastically if the canvas content is opaque. Because [alpha(composite) == 255](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/engine/PixelOps.kt#9-10) when the canvas is opaque, [unComposite](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/engine/PixelOps.kt#333-352) incorrectly returns the fully opaque, blurred composite, and sets it to the `sublayer`. This pulls the canvas *into* the stroke layer, causing opaque rectangular bounding box artifacts that completely overwrite the canvas at [endStroke](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/engine/BrushEngine.kt#135-141). 

To fix this, we need to blur the `sublayer`'s alpha channel *separately* from the RGB color channels to keep track of its actual blurred opacity. Then we can use the blurred alpha to reconstruct the correct original stroke color.

## Proposed Changes

### [app/src/main/java/com/propaint/app/engine/CanvasDocument.kt](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/engine/CanvasDocument.kt)
We will rewrite the inner loop of [rebuildCompositeTile](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/engine/CanvasDocument.kt#299-366) to track `clipBaseTile` robustly. 
We will introduce a boolean `isBaseVisible` and an explicit `clipBaseState` so that if a base layer is either explicitly hidden OR fully transparent, any clipped layers above it correctly disappear entirely instead of rendering against the wrong base.

#### [MODIFY] CanvasDocument.kt
- Change `var clipBaseTile: IntArray? = null` to also include logic for tracking if the current base layer is invisible (`isBaseVisible = false`).
- Add checks at the start of the `for (layer in _layers)` loop:
  - If `!layer.isClipToBelow`, assess this layer as the NEW base layer. Check if `layer.isVisible && layer.opacity > 0` and whether `mainTile == null && subTile == null`. Update `clipBaseTile` and `isBaseVisible` accordingly.
  - If `layer.isClipToBelow`, immediately skip rendering if `!isBaseVisible` or if `clipBaseTile == null`.
  - Apply modifications to effectively maintain `clipBaseTile` independently of loop `continue` paths.

### [app/src/main/java/com/propaint/app/engine/BrushEngine.kt](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/engine/BrushEngine.kt)
We need to track the true blurred alpha of the stroke. We will create a parallel alpha blur pass or simply extract the alpha out. Actually, an easier method to resolve the alpha is to blur the `sublayer` pixel's straight alpha in a separate buffer or just pack it in `blurSrc` somehow? No, `PixelOps.blendSrcOver` loses the original [sa](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/engine/PixelOps.kt#105-108) if [da](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/viewmodel/PaintViewModel.kt#706-710) is 255. We must pass the [sa](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/engine/PixelOps.kt#105-108) along.
Wait, if we blur `sublayer` directly, we get the blurred [sa](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/engine/PixelOps.kt#105-108). So we can just blur `sublayer` by itself!
Why did we blur `sublayer SrcOver content`? Because brushes like Watercolor smear the existing canvas along with the stroke.
So we can do:
1. Blur `sublayer SrcOver content` to get the smeared RGB.
2. Blur `sublayer` by itself to get the blurred alpha (stroke presence).
3. Combine them: the new `sublayer` pixel is the smeared RGB with the blurred alpha!

#### [MODIFY] BrushEngine.kt & PixelOps.kt
- In `BrushEngine.blurAreaOnSurface`, create a separate blur pass for the `sublayer` to get its true blurred alpha boundaries. (Or since [separableBoxBlurPass](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/engine/BrushEngine.kt#455-513) is fast, just do it twice: once for [composite](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/engine/PixelOps.kt#264-284), once for `sublayer`).
- Use the blurred `sublayer` alpha alongside the un-composited RGB derived from the blurred [composite](file:///c:/Users/quinella/Desktop/propaint_v2/app/src/main/java/com/propaint/app/engine/PixelOps.kt#264-284).
- Simplify `PixelOps.unComposite` to take the true `subA` as a parameter to avoid impossible algebraic deductions when `dst` is opaque.

## Verification Plan

### Automated Tests
- N/A - The user has a native Android app codebase. I lack physical devices/emulators to run an integration test easily. This is a CPU-side array iteration rewrite, so logic can largely be checked visually by code inspection.

### Manual Verification
1. I will ask the user to test adding an empty base layer and a filled clipping layer over it. The clipping layer should correctly not appear.
2. I will ask the user to toggle visibility on a base layer that has clipping children. The children should also disappear.
3. Use the app to rapidly switch brushes and see if visual flickering or rendering issues persist.
