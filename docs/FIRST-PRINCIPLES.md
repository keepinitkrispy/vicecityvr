PSVR1 Project — First-Principles Architectural Findings

We previously abstracted this entire problem away from conventional VR rendering, depth reconstruction, emulation, and hardware compatibility. That exercise substantially changed our understanding of what actually needs to be computed.

1. Reconstruct the experience, not the game

We do not need to reconstruct GTA's geometry, access its engine, replicate its renderer, or generate a complete 3D environment.

We need to reproduce the visual evidence that causes the brain to perceive a three-dimensional environment.

Those are fundamentally different computational objectives.

The PS5 already performs the overwhelming majority of the expensive rendering work. Its output contains usable information about geometry, movement, lighting, perspective, occlusion, and object relationships.

Our job is to exploit that existing information rather than unnecessarily regenerate it.

2. Motion relationships may be cheaper than depth

Imagine two points occupying exactly the same pixel:

- A speck on your glasses.
- A streetlight in the distance.

When you turn your head, the speck remains attached to your field of vision while the streetlight moves.

When you move forward, the streetlight behaves differently from the nearby pavement.

When you switch to a rear-facing view, the streetlight disappears while the speck remains.

The distinguishing characteristic is not necessarily their absolute depth. It is how they respond to changes.

What stays attached to what when something moves?

That relationship can become our fundamental unit of computation.

Rather than estimating the exact depth of every pixel every frame, we can identify groups of pixels that respond similarly to movement and assign them shared transformation rules.

Possible groups include:

- Screen-locked interface elements.
- Camera-attached objects.
- Near environmental objects.
- Distant environmental objects.
- Ground and road surfaces.
- Independently moving entities.
- Effectively infinite backgrounds.

These groups do not require perfect semantic identification. They require sufficiently accurate behavioral classification to reproduce convincing parallax and binocular disparity.

3. Separate learning from rendering

Conventional approaches repeatedly calculate expensive information that often hasn't materially changed.

Our architecture should instead follow three principles:

Learn infrequently. Reproject continuously. Correct selectively.

Discover motion relationships when new evidence becomes available.

Retain those relationships across frames.

Use inexpensive GPU transformations to produce the two eye images continuously.

Spend computational resources only when existing relationships become inconsistent, new objects appear, or previously occluded regions become visible.

Do not independently reconstruct the scene for each eye. Both eyes should derive from one shared scene-response representation.

4. Exploit mathematical shortcuts

We demonstrated numerically that, under the appropriate flat-ground and pinhole-camera assumptions, stereo disparity across a road surface can be expressed as a simple linear relationship with distance from the horizon.

A numerical check across 30,000 points produced an error below 10⁻¹² pixels for that mathematical identity.

This means that when we can identify an applicable road plane, we do not need a neural depth model to calculate its entire disparity field.

We also tested a curved presentation surface represented by a 64 × 32 mesh, producing approximately 0.403 pixels of sampled approximation error with roughly 480 times fewer geometry evaluations than per-pixel evaluation.

These were mathematical and computational tests, not end-to-end headset performance measurements. However, they demonstrate why identifying reusable relationships is potentially more valuable than optimizing brute-force reconstruction.

5. We do not need a 360-degree reconstruction

The game already provides a rear-facing camera function through R3.

Treat this as an existing capability we can exploit rather than something we need to reproduce.

We only need a convincing representation of the currently visible region, with limited head-relative movement. Larger changes in viewing direction can use the game's existing camera controls.

The R3 transition is a known discrete change in viewpoint. It should invalidate inappropriate temporal history instead of triggering an expensive attempt to reconcile incompatible images.

This eliminates the requirement for a persistent 360-degree world model.

6. Decouple headset refresh from game rendering

A 30 FPS game does not necessarily require every component of the headset experience to operate at 30 FPS.

The source image can update at the game's native rate while the headset compositor updates its presentation at a higher supported rate.

Head-relative reprojection, optical correction, image positioning, and binocular presentation should be independent of source-frame acquisition wherever possible.

This will not create genuinely new game simulation frames, but it can make head-relative presentation more responsive.

7. Proposed architecture

PS5 rendered video → Pixel 11 video acquisition → persistent motion-response representation → shared stereo synthesis → head-relative reprojection → optical correction → PSVR1.

The expensive operations should be asynchronous to the presentation loop wherever practical.

The Pixel should not attempt to become a second PS5.

It should act as a specialized perceptual compositor.

8. Immediate implementation implications

The current headset calibration problem is substantially simpler than the eventual stereo reconstruction problem.

We should first establish:

1. Confirmed native PSVR1 VR mode.
2. Independent image delivery to each eye.
3. Identical source imagery for both eyes.
4. Controller-adjustable per-eye position and scale.
5. Saved calibration parameters.
6. Independently toggleable optical correction.

Do not make neural depth estimation, lens distortion research, head tracking, video upscaling, or full stereo reconstruction prerequisites for testing basic image delivery and alignment.

Once that foundation works, progressively introduce controlled binocular disparity, motion-response groups, ground-plane shortcuts, temporal reuse, and head-relative reprojection.

Each capability should be independently testable without destabilizing the others.

9. The governing principle

Before implementing an expensive operation, determine whether the information it produces is actually necessary to create the intended perceptual effect.

Then determine whether that information already exists implicitly in another part of the system.

Then determine whether it can be represented as a cheaper relationship, reused over time, or approximated below a perceptually meaningful error threshold.

The objective is not to make conventional VR reconstruction marginally faster.

The objective is to eliminate as much of conventional VR reconstruction as possible while retaining the experience it produces.

Do not treat hardware limitations derived from the conventional approach as proof that this alternative approach cannot work. Establish the actual computational requirements of the alternative first.

The remaining genuine uncertainties concern observable scene information, disocclusion, video latency, tracking integration, and physical display compatibility. These should be measured independently rather than assumed to be prohibitive.