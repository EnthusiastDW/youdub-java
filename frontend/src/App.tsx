import { Routes, Route } from "react-router-dom";
import HomePage from "./pages/HomePage";
import TaskDetailPage from "./pages/TaskDetailPage";
import SettingsPage from "./pages/SettingsPage";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/tasks/:id" element={<TaskDetailPage />} />
      <Route path="/settings" element={<SettingsPage />} />
    </Routes>
  );
}
