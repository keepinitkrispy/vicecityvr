// Vice City Bodycam: bounded camera response for stereo rendering.
// Apply the SAME view transform to both eyes; never introduce independent eye shake.
// Inputs are normalized controller axes and headset yaw/pitch in radians.
// This is a view-model prototype, not an OpenXR driver or GTA camera hook.
export class BodycamCamera {
  constructor({maxYaw=0.055,maxPitch=0.035,responseHz=7,returnHz=5}={}) {
    this.maxYaw=maxYaw; this.maxPitch=maxPitch;
    this.responseHz=responseHz; this.returnHz=returnHz;
    this.yaw=0; this.pitch=0;
  }
  update({dt,stickX=0,stickY=0,headYaw=0,headPitch=0}={}) {
    if(!Number.isFinite(dt)||dt<0) throw new Error('Invalid dt');
    const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
    const safe=v=>Number.isFinite(v)?clamp(v,-1,1):0;
    const x=safe(stickX), y=safe(stickY);
    const step=clamp(dt,0,0.05); // bound hitches; no giant recovery jump
    const targetYaw=-x*this.maxYaw, targetPitch=y*this.maxPitch;
    const hz=(Math.abs(x)+Math.abs(y)>0.015)?this.responseHz:this.returnHz;
    const alpha=1-Math.exp(-2*Math.PI*hz*step);
    this.yaw+=alpha*(targetYaw-this.yaw);
    this.pitch+=alpha*(targetPitch-this.pitch);
    // Head pose is never smoothed here: tracking should use latest available pose.
    return {yaw:headYaw+this.yaw,pitch:headPitch+this.pitch,
      cameraLagYaw:this.yaw,cameraLagPitch:this.pitch};
  }
  reset(){this.yaw=0;this.pitch=0;}
}
