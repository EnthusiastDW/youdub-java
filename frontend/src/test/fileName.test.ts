import { describe, it, expect } from "vitest";
import { coverDownloadName } from "@/utils/fileName";

describe("coverDownloadName", () => {
  it("returns title with -封面.jpg suffix", () => {
    expect(coverDownloadName("My Video")).toBe("My Video-封面.jpg");
  });

  it("returns 'cover-封面.jpg' when title is undefined", () => {
    expect(coverDownloadName(undefined)).toBe("cover-封面.jpg");
  });

  it("sanitizes illegal filename characters", () => {
    expect(coverDownloadName("a/b:c*d?e\"f<g|h")).toBe("a_b_c_d_e_f_g_h-封面.jpg");
  });

  it("handles Chinese title", () => {
    expect(coverDownloadName("我的视频")).toBe("我的视频-封面.jpg");
  });

  it("handles title with only illegal characters", () => {
    expect(coverDownloadName("?><|")).toBe("____-封面.jpg");
  });
});
