package io.rapidpro.surveyor.activity;

import android.os.AsyncTask;
import android.widget.Toast;

import java.io.File;

import io.rapidpro.surveyor.Surveyor;
import io.rapidpro.surveyor.data.Submission;
import io.rapidpro.surveyor.ui.BlockingProgress;

/**
 * AsyncTask for sending submissions to the server
 */
class SubmitSubmissions extends AsyncTask<String, Void, Void> {

    private BaseActivity m_activity;
    private File[] m_submissions;
    private BlockingProgress m_progress;
    private int m_error;

//    private static final int REQUEST_CODE_READ_PHONE_STATE = 0;



    public SubmitSubmissions(BaseActivity activity, File[] submissions, BlockingProgress progress) {
        m_activity = activity;
        m_submissions = submissions;
        m_progress = progress;

//        // Assume thisActivity is the current activity
//        int permissionCheck = ContextCompat.checkSelfPermission(Surveyor.get(),
//                Manifest.permission.READ_SMS);
//        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
//            ActivityCompat.requestPermissions(activity,
//                    new String[]{Manifest.permission.READ_PHONE_STATE},
//                    REQUEST_CODE_READ_PHONE_STATE); // define this constant yourself
//        } else {
//            // you have the permission
//            Surveyor.LOG.d("you have the permission");
//        }
    }

    @Override
    protected Void doInBackground(String... params) {

        for (File submission : m_submissions) {
            Submission sub = Submission.load(m_activity.getUsername(), submission);
            if (sub != null) {
                try {
                    sub.submit();
                } catch (Throwable t) {
                    m_error = m_activity.getRapidProService().getErrorMessage(t);
                    Surveyor.LOG.e("Failed to submit flow run, error = " + m_error, t);
                }
            }

            m_progress.incrementProgressBy(1);
        }
        return null;
    }

    @Override
    protected void onPostExecute(Void result) {
        m_progress.dismiss();
        m_activity.refresh();

        if (m_error > 0) {
            Toast.makeText(m_activity, m_error, Toast.LENGTH_SHORT).show();
        }
    }
}
