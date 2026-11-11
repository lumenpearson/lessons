# Android decomposition, part C

Part C of [the approved design](2026-10-05-android-decomposition-design.md) follows
#451 and #625 under epic #273.

1. Move each onboarding step as a whole. Keep navigation and transitions in
   `OnboardingScreen`, and put shared layout and reveal helpers in `OnboardingParts`.
   Point the legal-footer regression test at the welcome step and require its declaration.
2. Split the floating toolbar within its package. Keep composition state and pointer
   input in their original functions. Move the geometry test allowances with the two
   tab circles and the action badge. Retain the existing detekt budget sizes.
3. Keep the shell transition and back handlers inline. Move the action alongside its
   existing classifier. Select toolbar content, back callbacks and scroll holders by
   destination. Test the reordered tab before reading the pager target.
4. Run Android unit tests, both APK assemblies and detekt, then compare the completed
   reports with the published counts. Submit the working branch to dev before promotion.
